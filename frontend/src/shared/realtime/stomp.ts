import { Client } from '@stomp/stompjs'
import { readCookie } from '../api/client.ts'

export type ConnectionState = 'connecting' | 'connected' | 'reconnecting'

export interface TopicHandlers {
  onMessage: (body: string) => void
  onState: (state: ConnectionState) => void
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

/**
 * Subscribes to a STOMP topic on the backend (`/ws`, session cookie + CSRF header on CONNECT).
 * Reconnects automatically and re-subscribes; the server replays buffered frames on every
 * subscribe, so callers de-duplicate. Returns the function that tears everything down.
 */
export function subscribeToTopic(destination: string, handlers: TopicHandlers, options: SubscribeOptions = {}): () => void {
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
    onWebSocketClose: () => handlers.onState('reconnecting'),
  })
  handlers.onState('connecting')
  client.activate()
  return () => {
    void client.deactivate()
  }
}
