import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { TopicHandlers } from '../../../shared/realtime/stomp.ts'

const subscribe = vi.fn()
vi.mock('../../../shared/realtime/stomp.ts', () => ({ subscribeToTopic: (...a: unknown[]) => subscribe(...a) }))

import { applyFrames, MAX_LOG_LINES, useLogStream, type Frame, type LogStreamState } from './logStream.ts'

const empty: LogStreamState = { key: 'k', lines: [], truncated: false, end: null, notices: [], services: [], serviceEnds: {}, refused: false }
const line = (seq: number): Frame => ({ seq, type: 'LINE', stream: 'STDOUT', line: `line ${seq}` })

describe('applyFrames', () => {
  it('keeps the newest MAX_LOG_LINES lines and flags truncation, for a batch spanning the cap', () => {
    const full = applyFrames(empty, Array.from({ length: MAX_LOG_LINES }, (_, i) => line(i)), false)
    expect(full.truncated).toBe(false)
    const next = applyFrames(full, [line(MAX_LOG_LINES), line(MAX_LOG_LINES + 1)], false)
    expect(next.lines).toHaveLength(MAX_LOG_LINES)
    expect(next.lines[0].seq).toBe(2)
    expect(next.lines.at(-1)?.seq).toBe(MAX_LOG_LINES + 1)
    expect(next.truncated).toBe(true)
  })

  it('applies status frames in order between lines', () => {
    const state = applyFrames(empty, [line(1), { seq: 2, type: 'STATUS', status: 'ENDED' }, line(3)], false)
    expect(state.lines.map((l) => l.seq)).toEqual([1, 3])
    expect(state.end).toEqual({ status: 'ENDED' })
  })

  it('collects services in first-seen order for the combined stream', () => {
    const state = applyFrames(empty, [{ ...line(1), service: 'db' }, { ...line(2), service: 'web' }, { ...line(3), service: 'db' }], true)
    expect(state.services).toEqual(['db', 'web'])
  })
})

describe('useLogStream batching', () => {
  let handlers: TopicHandlers
  let frames: FrameRequestCallback[]
  beforeEach(() => {
    frames = []
    vi.stubGlobal('requestAnimationFrame', (cb: FrameRequestCallback) => frames.push(cb))
    vi.stubGlobal('cancelAnimationFrame', () => {})
    subscribe.mockReset()
    subscribe.mockImplementation((_t: string, h: TopicHandlers) => {
      handlers = h
      return vi.fn()
    })
  })
  afterEach(() => vi.unstubAllGlobals())

  it('applies many frames on a full 5000-line buffer in one state update, not one per line', () => {
    let renders = 0
    const { result } = renderHook(() => {
      renders++
      return useLogStream('/t', '100', false, false)
    })
    const send = (from: number, n: number) => act(() => {
      for (let i = 0; i < n; i++) handlers.onMessage(JSON.stringify(line(from + i)))
      frames.splice(0).forEach((cb) => cb(0))
    })
    send(0, MAX_LOG_LINES)
    expect(result.current.lines).toHaveLength(MAX_LOG_LINES)

    const before = renders
    send(MAX_LOG_LINES, 100)
    expect(renders - before).toBe(1)
    expect(result.current.lines).toHaveLength(MAX_LOG_LINES)
    expect(result.current.lines.at(-1)?.seq).toBe(MAX_LOG_LINES + 99)
  })

  it('schedules a single animation frame for a burst and shows nothing before it fires', () => {
    const { result } = renderHook(() => useLogStream('/t', '100', false, false))
    act(() => {
      for (let i = 0; i < 50; i++) handlers.onMessage(JSON.stringify(line(i)))
    })
    expect(frames).toHaveLength(1)
    expect(result.current.lines).toHaveLength(0)
    act(() => frames.splice(0).forEach((cb) => cb(0)))
    expect(result.current.lines).toHaveLength(50)
  })
})

describe('useLogStream flush paths', () => {
  let handlers: TopicHandlers
  let frames: Map<number, FrameRequestCallback>
  let nextId: number
  const unsubscribe = vi.fn()
  const msg = (f: Frame) => act(() => handlers.onMessage(JSON.stringify(f)))
  const fireFrames = () => act(() => [...frames.values()].forEach((cb) => (frames.clear(), cb(0))))

  beforeEach(() => {
    vi.useFakeTimers()
    frames = new Map()
    nextId = 1
    vi.stubGlobal('requestAnimationFrame', (cb: FrameRequestCallback) => (frames.set(nextId, cb), nextId++))
    vi.stubGlobal('cancelAnimationFrame', (id: number) => frames.delete(id))
    unsubscribe.mockReset()
    subscribe.mockReset()
    subscribe.mockImplementation((_t: string, h: TopicHandlers) => {
      handlers = h
      return unsubscribe
    })
  })
  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  it('unmounting cancels the pending frame and the fallback timer', () => {
    const error = vi.spyOn(console, 'error').mockImplementation(() => {})
    const { unmount } = renderHook(() => useLogStream('/t', '100', false, false))
    msg(line(1))
    expect(frames.size).toBe(1)
    expect(vi.getTimerCount()).toBe(1)
    unmount()
    expect(frames.size).toBe(0)
    expect(vi.getTimerCount()).toBe(0)
    act(() => void vi.advanceTimersByTime(1000))
    expect(error).not.toHaveBeenCalled()
    error.mockRestore()
  })

  it('flushes after 250 ms when the animation frame never fires (hidden tab)', () => {
    const { result } = renderHook(() => useLogStream('/t', '100', false, false))
    msg(line(1))
    act(() => void vi.advanceTimersByTime(249))
    expect(result.current.lines).toHaveLength(0)
    act(() => void vi.advanceTimersByTime(1))
    expect(result.current.lines).toHaveLength(1)
    expect(frames.size).toBe(0) // the stale frame request was cancelled
  })

  it('does not apply anything again when the leftover timer would fire after a frame flush', () => {
    let renders = 0
    const { result } = renderHook(() => {
      renders++
      return useLogStream('/t', '100', false, false)
    })
    msg(line(1))
    fireFrames()
    expect(result.current.lines).toHaveLength(1)
    const before = renders
    act(() => void vi.advanceTimersByTime(1000))
    expect(renders).toBe(before)
    expect(vi.getTimerCount()).toBe(0)
  })

  it.each([
    ['ENDED', { status: 'ENDED' }],
    ['ERROR', { status: 'ERROR', code: 'X' }],
  ] as const)('a %s status frame applies the queued lines first, then ends the stream', (status, end) => {
    const { result } = renderHook(() => useLogStream('/t', '100', false, false))
    msg(line(1))
    msg(line(2))
    msg({ seq: 3, type: 'STATUS', status, line: status === 'ERROR' ? 'X' : undefined })
    expect(result.current.lines.map((l) => l.seq)).toEqual([1, 2])
    expect(result.current.end).toEqual(end)
    expect(unsubscribe).toHaveBeenCalledTimes(1)
  })

  it('a STOMP error applies the queued lines before marking the stream refused', () => {
    const { result } = renderHook(() => useLogStream('/t', '100', false, false))
    msg(line(1))
    act(() => handlers.onError?.('refused'))
    expect(result.current.lines.map((l) => l.seq)).toEqual([1])
    expect(result.current.refused).toBe(true)
    expect(unsubscribe).toHaveBeenCalledTimes(1)
  })
})
