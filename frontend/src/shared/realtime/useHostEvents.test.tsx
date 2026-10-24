import { act, cleanup, render } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, beforeEach, describe, expect, it, vi, type MockInstance } from 'vitest'
import type { TopicHandlers } from './stomp.ts'

const subscribe = vi.fn()
vi.mock('./stomp.ts', () => ({ subscribeToTopic: (...a: unknown[]) => subscribe(...a) }))

import { EVENT_THROTTLE_MS, isEventStreamLive, useHostEvents } from './useHostEvents.ts'

let handlers: TopicHandlers
const unsubscribe = vi.fn()
let client: QueryClient
let spy: MockInstance<QueryClient['invalidateQueries']>

function Probe({ hostId }: { hostId?: string }) {
  useHostEvents(hostId)
  return null
}
const mount = (hostId?: string) =>
  render(
    <QueryClientProvider client={client}>
      <Probe hostId={hostId} />
    </QueryClientProvider>,
  )
const send = (frame: object) => act(() => handlers.onMessage(JSON.stringify(frame)))
const flush = () => act(() => vi.advanceTimersByTime(EVENT_THROTTLE_MS + 1))
const keys = () => spy.mock.calls.map((c: unknown[]) => (c[0] as { queryKey: unknown[] }).queryKey.join('/'))

beforeEach(() => {
  vi.useFakeTimers()
  client = new QueryClient()
  spy = vi.spyOn(client, 'invalidateQueries')
  subscribe.mockReset()
  unsubscribe.mockReset()
  subscribe.mockImplementation((_d: string, h: TopicHandlers) => {
    handlers = h
    return unsubscribe
  })
})
afterEach(() => {
  cleanup()
  vi.useRealTimers()
})

describe('useHostEvents', () => {
  it('subscribes to the host topic and unsubscribes on unmount', () => {
    const { unmount } = mount('h1')
    expect(subscribe.mock.calls[0][0]).toBe('/topic/hosts/h1/events')
    unmount()
    expect(unsubscribe).toHaveBeenCalledOnce()
  })

  it('does not subscribe without a host', () => {
    mount(undefined)
    expect(subscribe).not.toHaveBeenCalled()
  })

  it('invalidates only the matching lists (plus overview), throttled into one batch', () => {
    mount('h1')
    send({ seq: 1, type: 'EVENT', events: [{ type: 'IMAGE', action: 'pull', id: 'i' }, { type: 'IMAGE', action: 'tag', id: 'i' }] })
    expect(spy).not.toHaveBeenCalled()
    flush()
    expect(keys()).toEqual(['hosts/h1/images', 'hosts/overview'])
  })

  it('container events refresh containers and deployments', () => {
    mount('h1')
    send({ seq: 1, type: 'EVENT', events: [{ type: 'CONTAINER', action: 'start', id: 'c' }] })
    flush()
    expect(keys()).toEqual(['hosts/h1/containers', 'hosts/h1/deployments', 'hosts/overview'])
  })

  it('invalidates everything on CONNECTED and on reconnect, and when events were omitted', () => {
    mount('h1')
    send({ seq: 1, type: 'STATUS', status: 'CONNECTED' })
    flush()
    expect(keys()).toEqual(['hosts/h1', 'hosts/overview'])
    spy.mockClear()
    send({ seq: 2, type: 'STATUS', status: 'DISCONNECTED', code: 'UNREACHABLE' })
    send({ seq: 3, type: 'STATUS', status: 'CONNECTED' })
    flush()
    expect(keys()).toEqual(['hosts/h1', 'hosts/overview'])
    spy.mockClear()
    send({ seq: 4, type: 'EVENT', events: [], omitted: 12 })
    flush()
    expect(keys()).toEqual(['hosts/h1', 'hosts/overview'])
  })

  it('reports a live stream between CONNECTED and DISCONNECTED/unmount', () => {
    const { unmount } = mount('h1')
    expect(isEventStreamLive()).toBe(false)
    send({ seq: 1, type: 'STATUS', status: 'CONNECTED' })
    expect(isEventStreamLive()).toBe(true)
    send({ seq: 2, type: 'STATUS', status: 'DISCONNECTED' })
    expect(isEventStreamLive()).toBe(false)
    send({ seq: 3, type: 'STATUS', status: 'CONNECTED' })
    unmount()
    expect(isEventStreamLive()).toBe(false)
  })

  it('ignores malformed frames', () => {
    mount('h1')
    act(() => handlers.onMessage('not json'))
    flush()
    expect(spy).not.toHaveBeenCalled()
  })
})
