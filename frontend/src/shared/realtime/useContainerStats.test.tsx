import { useEffect } from 'react'
import { act, cleanup, render } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { TopicHandlers } from './stomp.ts'

const subscribe = vi.fn()
vi.mock('./stomp.ts', () => ({ subscribeToTopic: (...a: unknown[]) => subscribe(...a) }))

import { MAX_SAMPLES, useContainerStats, type ContainerStats } from './useContainerStats.ts'

let handlers: TopicHandlers
const unsubscribe = vi.fn()
let latest: ContainerStats

function Probe({ hostId }: { hostId?: string }) {
  const stats = useContainerStats(hostId)
  useEffect(() => {
    latest = stats
  })
  return null
}
const sample = (containerId: string, cpu: number) => ({ containerId, cpu, memUsed: 10, memLimit: 100, netRx: 1, netTx: 2, blkRead: 3, blkWrite: 4 })
const send = (frame: object) => act(() => handlers.onMessage(JSON.stringify(frame)))

beforeEach(() => {
  subscribe.mockReset()
  unsubscribe.mockReset()
  subscribe.mockImplementation((_d: string, h: TopicHandlers) => {
    handlers = h
    return unsubscribe
  })
})
afterEach(cleanup)

describe('useContainerStats', () => {
  it('subscribes to the host stats topic and unsubscribes on unmount', () => {
    const { unmount } = render(<Probe hostId="h1" />)
    expect(subscribe.mock.calls[0][0]).toBe('/topic/hosts/h1/stats')
    unmount()
    expect(unsubscribe).toHaveBeenCalledTimes(1)
  })

  it('does not subscribe without a host id', () => {
    render(<Probe />)
    expect(subscribe).not.toHaveBeenCalled()
  })

  it('appends samples per container and drops containers missing from a frame', () => {
    render(<Probe hostId="h1" />)
    send({ seq: 1, samples: [sample('a', 1), sample('b', 5)] })
    send({ seq: 2, samples: [sample('a', 2)] })
    expect(latest.series.a.map((s) => s.cpu)).toEqual([1, 2])
    expect(latest.series.b).toBeUndefined()
    expect(latest.code).toBeNull()
  })

  it('keeps only the last 60 samples', () => {
    render(<Probe hostId="h1" />)
    for (let i = 1; i <= MAX_SAMPLES + 5; i++) send({ seq: i, samples: [sample('a', i)] })
    expect(latest.series.a).toHaveLength(MAX_SAMPLES)
    expect(latest.series.a.at(-1)?.cpu).toBe(MAX_SAMPLES + 5)
  })

  it('ignores malformed bodies', () => {
    render(<Probe hostId="h1" />)
    send({ seq: 3, samples: [sample('a', 1)] })
    act(() => handlers.onMessage('not json'))
    expect(latest.series.a).toHaveLength(1)
  })

  it('exposes the error code with empty samples', () => {
    render(<Probe hostId="h1" />)
    send({ seq: 1, samples: [sample('a', 1)] })
    send({ seq: 2, samples: [], code: 'UNREACHABLE' })
    expect(latest.code).toBe('UNREACHABLE')
    expect(latest.series).toEqual({})
  })
})
