import { Client } from '@stomp/stompjs'
import { readCookie } from '../api/client.ts'

export type ConnectionState = 'connecting' | 'connected' | 'reconnecting'

export interface TopicHandlers {
  onMessage: (body: string) => void
  onState: (state: ConnectionState) => void
  /** The broker answered with an ERROR frame (e.g. the subscription was refused). The connection is closed afterwards. */
  onError?: (message: string) => void
}

export interface SubscribeOptions {
  /** Extra SUBSCRIBE headers (e.g. per-subscription options the server reads). */
  headers?: Record<string, string>
}

const RECONNECT_DELAY_MS = 3000

function brokerUrl(): string {
  const scheme = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  return `${scheme}//${window.location.host}/ws`
}

export interface TopicChannel {
  /** Publishes a frame (STOMP SEND). Dropped silently while not connected. */
  publish: (destination: string, body: Uint8Array | string) => void
  /** Tears down the subscription and the connection. */
  close: () => void
}

/**
 * Like {@link subscribeToTopic}, but also lets the caller SEND frames over the same connection
 * (needed where one connection must both receive and send, e.g. the exec console).
 */
export function openTopicChannel(destination: string, handlers: TopicHandlers, options: SubscribeOptions = {}): TopicChannel {
  const client = new Client({
    brokerURL: brokerUrl(),
    reconnectDelay: RECONNECT_DELAY_MS,
    heartbeatIncoming: 10000,
    heartbeatOutgoing: 10000,
    // Re-read on every attempt: the token rotates with the session.
    beforeConnect: () => {
      client.connectHeaders = { 'X-XSRF-TOKEN': readCookie('XSRF-TOKEN') ?? '' }
    },
    onConnect: () => {
      handlers.onState('connected')
      client.subscribe(destination, (frame) => handlers.onMessage(frame.body), options.headers)
    },
    onStompError: (frame) => handlers.onError?.(frame.headers.message ?? ''),
    onWebSocketClose: () => handlers.onState('reconnecting'),
  })
  handlers.onState('connecting')
  client.activate()
  return {
    publish: (dest, body) => {
      if (!client.connected) return
      if (typeof body === 'string') client.publish({ destination: dest, body })
      else client.publish({ destination: dest, binaryBody: body, headers: { 'content-type': 'application/octet-stream' } })
    },
    close: () => {
      void client.deactivate()
    },
  }
}

/**
 * Subscribes to a STOMP topic on the backend (`/ws`, session cookie + CSRF header on CONNECT).
 * Reconnects automatically and re-subscribes; the server replays buffered frames on every
 * subscribe, so callers de-duplicate. Returns the function that tears everything down.
 */
export function subscribeToTopic(destination: string, handlers: TopicHandlers, options: SubscribeOptions = {}): () => void {
  return openTopicChannel(destination, handlers, options).close
}
