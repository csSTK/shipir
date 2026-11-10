import { act, renderHook } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { TopicHandlers } from '../../shared/realtime/stomp.ts'

const subscribe = vi.fn()
vi.mock('../../shared/realtime/stomp.ts', () => ({ subscribeToTopic: (...a: unknown[]) => subscribe(...a) }))

import { useChatStream } from './useChatStream.ts'

const handlersByTopic = new Map<string, TopicHandlers>()
const send = (topic: string, frame: object) => act(() => handlersByTopic.get(topic)!.onMessage(JSON.stringify(frame)))

beforeEach(() => {
  handlersByTopic.clear()
  subscribe.mockReset()
  subscribe.mockImplementation((topic: string, h: TopicHandlers) => {
    handlersByTopic.set(topic, h)
    return vi.fn()
  })
})

describe('useChatStream', () => {
  it('a late DONE refresh of the previous session does not wipe the next session live state', async () => {
    let resolveA!: () => void
    const onDone = vi.fn(() => new Promise<void>((r) => (resolveA = r)))
    const { result, rerender } = renderHook(({ id }) => useChatStream(id, onDone), { initialProps: { id: 'a' } })

    send('/topic/chat/a', { type: 'DONE', outcome: 'COMPLETE' })
    rerender({ id: 'b' })
    send('/topic/chat/b', { type: 'TOKEN', text: 'hello' })
    expect(result.current.text).toBe('hello')

    await act(async () => resolveA())
    expect(result.current.text).toBe('hello')
    expect(result.current.running).toBe(true)
  })

  it('clears the live state after DONE refresh of the current session', async () => {
    const { result } = renderHook(() => useChatStream('a', () => Promise.resolve()))
    send('/topic/chat/a', { type: 'TOKEN', text: 'hi' })
    await act(async () => send('/topic/chat/a', { type: 'DONE', outcome: 'COMPLETE' }))
    expect(result.current.text).toBe('')
    expect(result.current.running).toBe(false)
  })
})
