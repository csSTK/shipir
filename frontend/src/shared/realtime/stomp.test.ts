import { beforeEach, describe, expect, it, vi } from 'vitest'
import { subscribeToTopic, type TopicHandlers } from './stomp.ts'

// A stand-in for the stompjs client: records its configuration and lets a test play the broker's part
// (connect, ERROR frame, socket close) by calling the callbacks the module passed in.
const broker = vi.hoisted(() => {
  type Config = {
    reconnectDelay: number
    beforeConnect: () => void
    onConnect: () => void
    onStompError: (frame: { headers: Record<string, string>; body: string }) => void
    onWebSocketClose: () => void
  }
  class FakeClient {
    readonly config: Config
    connected = false
    connectHeaders: Record<string, string> = {}
    readonly subscribe = vi.fn()
    readonly publish = vi.fn()
    readonly activate = vi.fn()
    readonly deactivate = vi.fn(() => Promise.resolve())
    constructor(config: Config) {
      this.config = config
      broker.clients.push(this)
    }
  }
  return { clients: [] as FakeClient[], FakeClient }
})

vi.mock('@stomp/stompjs', () => ({ Client: broker.FakeClient }))

const DESTINATION = '/topic/hosts/h1/events'

let handlers: { onMessage: ReturnType<typeof vi.fn>; onState: ReturnType<typeof vi.fn>; onError: ReturnType<typeof vi.fn> }

function client() {
  const c = broker.clients.at(-1)
  if (!c) throw new Error('no client was created')
  return c
}

function open(withErrorHandler = true) {
  const topicHandlers: TopicHandlers = {
    onMessage: vi.fn(),
    onState: vi.fn(),
    ...(withErrorHandler ? { onError: vi.fn() } : {}),
  }
  handlers = topicHandlers as typeof handlers
  return subscribeToTopic(DESTINATION, topicHandlers, { headers: { tail: '100' } })
}

beforeEach(() => {
  broker.clients.length = 0
  document.cookie = 'XSRF-TOKEN=token-1'
})

describe('subscribeToTopic: STOMP ERROR frame', () => {
  it('passes the broker message of an ERROR frame to onError', () => {
    open()

    client().config.onStompError({ headers: { message: 'SETUP_REQUIRED' }, body: '' })

    expect(handlers.onError).toHaveBeenCalledTimes(1)
    expect(handlers.onError).toHaveBeenCalledWith('SETUP_REQUIRED')
  })

  it('passes an empty message to onError when the ERROR frame has no message header', () => {
    open()

    client().config.onStompError({ headers: {}, body: '' })

    expect(handlers.onError).toHaveBeenCalledWith('')
  })

  it('does not change the connection state, subscribe or deliver messages after an ERROR frame', () => {
    open()
    const subscribedBefore = client().subscribe.mock.calls.length

    client().config.onStompError({ headers: { message: 'FORBIDDEN' }, body: '' })

    // onError is the only reaction: the state stays as it was and nothing is subscribed or delivered.
    expect(handlers.onState.mock.calls).toEqual([['connecting']])
    expect(client().subscribe.mock.calls.length).toBe(subscribedBefore)
    expect(handlers.onMessage).not.toHaveBeenCalled()
  })

  it('does not throw when the ERROR frame arrives and no onError handler was given', () => {
    open(false)

    expect(() => client().config.onStompError({ headers: { message: 'x' }, body: '' })).not.toThrow()
  })
})

describe('subscribeToTopic: reconnect and state after an ERROR', () => {
  it('reports reconnecting when the broker closes the socket, and asks stompjs to retry after 3 s', () => {
    open()
    client().config.onConnect()
    client().config.onStompError({ headers: { message: 'FORBIDDEN' }, body: '' })

    client().config.onWebSocketClose()

    expect(handlers.onState).toHaveBeenLastCalledWith('reconnecting')
    expect(client().config.reconnectDelay).toBe(3000)
    // stompjs retries by itself: the module must not deactivate the client on an ERROR.
    expect(client().deactivate).not.toHaveBeenCalled()
  })

  it('subscribes again with the same headers after reconnecting, re-reading the CSRF token', () => {
    open()
    client().config.onConnect()
    client().config.onStompError({ headers: { message: 'x' }, body: '' })
    client().config.onWebSocketClose()

    document.cookie = 'XSRF-TOKEN=token-2'
    client().config.beforeConnect()
    client().config.onConnect()

    expect(client().connectHeaders).toEqual({ 'X-XSRF-TOKEN': 'token-2' })
    expect(handlers.onState.mock.calls.map((c) => c[0])).toEqual(['connecting', 'connected', 'reconnecting', 'connected'])
    expect(client().subscribe).toHaveBeenCalledWith(DESTINATION, expect.any(Function), { tail: '100' })
  })

  it('keeps delivering messages to onMessage on the new connection after a reconnect', () => {
    open()
    client().config.onConnect()
    client().config.onStompError({ headers: { message: 'x' }, body: '' })
    client().config.onWebSocketClose()
    client().config.onConnect()

    const onFrame = client().subscribe.mock.calls.at(-1)?.[1] as (frame: { body: string }) => void
    onFrame({ body: '{"seq":1}' })

    expect(handlers.onMessage).toHaveBeenCalledWith('{"seq":1}')
  })
})

describe('subscribeToTopic: close', () => {
  it('deactivates the client when the returned teardown is called', () => {
    const close = open()

    close()

    expect(client().deactivate).toHaveBeenCalledTimes(1)
  })
})
