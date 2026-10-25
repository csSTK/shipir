import { useEffect, useState } from 'react'
import { subscribeToTopic, type ConnectionState } from '../../../shared/realtime/stomp.ts'

export type TailOption = '100' | '200' | '500' | '1000' | 'all'
export type LogEnd = { status: 'ENDED' } | { status: 'ERROR'; code: string }

export interface LogLine {
  seq: number
  stream: 'STDOUT' | 'STDERR'
  text: string
  timestamp?: string
  /** Compose service; only set on the combined stream of a deployment. */
  service?: string
  container?: string
  containerId?: string
}

interface Frame {
  seq: number
  type: 'LINE' | 'STATUS'
  stream?: 'STDOUT' | 'STDERR'
  line?: string
  status?: 'ENDED' | 'ERROR'
  timestamp?: string
  service?: string
  container?: string
  containerId?: string
}

/** Oldest lines are dropped beyond this, so "all" cannot exhaust the browser. */
export const MAX_LOG_LINES = 5000

function parseFrame(body: string): Frame | null {
  try {
    const f: unknown = JSON.parse(body)
    if (!f || typeof f !== 'object' || !('seq' in f) || typeof f.seq !== 'number') return null
    if (!('type' in f) || (f.type !== 'LINE' && f.type !== 'STATUS')) return null
    return f as Frame
  } catch {
    return null
  }
}

export interface LogStreamState {
  key: string
  lines: LogLine[]
  /** True once the oldest lines were dropped because of {@link MAX_LOG_LINES}. */
  truncated: boolean
  /** Stream is over (single container only). */
  end: LogEnd | null
  /** Project-level problem codes (combined stream only), e.g. CONTAINER_LIMIT; the stream stays open. */
  notices: string[]
  /** Services in first-seen order (combined stream only), stable basis for colours. */
  services: string[]
  /** Services whose container ended or failed. */
  serviceEnds: Record<string, 'ENDED' | 'ERROR'>
  /** The broker refused the subscription. */
  refused: boolean
}

const fresh = (key: string): LogStreamState => ({ key, lines: [], truncated: false, end: null, notices: [], services: [], serviceEnds: {}, refused: false })

function applyFrame(prev: LogStreamState, frame: Frame, combined: boolean): LogStreamState {
  if (frame.type === 'LINE') {
    const line: LogLine = {
      seq: frame.seq,
      stream: frame.stream === 'STDERR' ? 'STDERR' : 'STDOUT',
      text: frame.line ?? '',
      timestamp: frame.timestamp,
    }
    if (combined) {
      line.service = frame.service
      line.container = frame.container
      line.containerId = frame.containerId
    }
    const all = [...prev.lines, line]
    const services = combined && line.service && !prev.services.includes(line.service) ? [...prev.services, line.service] : prev.services
    return { ...prev, lines: all.slice(-MAX_LOG_LINES), truncated: prev.truncated || all.length > MAX_LOG_LINES, services }
  }
  if (frame.status !== 'ENDED' && frame.status !== 'ERROR') return prev
  if (!combined) {
    return { ...prev, end: frame.status === 'ENDED' ? { status: 'ENDED' } : { status: 'ERROR', code: frame.line ?? 'UNKNOWN' } }
  }
  if (frame.service) {
    const services = prev.services.includes(frame.service) ? prev.services : [...prev.services, frame.service]
    return { ...prev, services, serviceEnds: { ...prev.serviceEnds, [frame.service]: frame.status } }
  }
  const code = frame.line ?? 'UNKNOWN'
  return prev.notices.includes(code) ? prev : { ...prev, notices: [...prev.notices, code] }
}

/**
 * Live log over one STOMP topic. Options are fixed per subscription, so changing them resubscribes and starts with an
 * empty view. Frames are de-duplicated by `seq` (the server replays). `combined` selects the multi-container
 * semantics (per-service status, project-level notices) instead of "a STATUS ends the stream".
 */
export function useLogStream(topic: string, tail: TailOption, timestamps: boolean, combined: boolean) {
  const key = `${topic}/${tail}/${timestamps}`
  const [state, setState] = useState<LogStreamState>(() => fresh(key))
  const [conn, setConnection] = useState<{ key: string; state: ConnectionState }>({ key, state: 'connecting' })

  useEffect(() => {
    let lastSeq = -1
    let done = false
    const update = (fn: (prev: LogStreamState) => LogStreamState) =>
      // A state from a previous subscription is replaced, so the first frame starts fresh.
      setState((prev) => fn(prev.key === key ? prev : fresh(key)))
    const unsubscribe = subscribeToTopic(
      topic,
      {
        onState: (s) => {
          if (!done) setConnection({ key, state: s })
        },
        onMessage: (body) => {
          const frame = parseFrame(body)
          if (!frame || done || frame.seq <= lastSeq) return
          lastSeq = frame.seq
          update((prev) => applyFrame(prev, frame, combined))
          if (!combined && frame.type === 'STATUS' && (frame.status === 'ENDED' || frame.status === 'ERROR')) {
            done = true
            unsubscribe() // stream is complete: stop reconnecting
          }
        },
        onError: () => {
          if (done) return
          done = true
          update((prev) => ({ ...prev, refused: true }))
          unsubscribe() // refused (e.g. no permission): retrying would only repeat the refusal
        },
      },
      { headers: { tail, timestamps: String(timestamps) } },
    )
    return () => {
      done = true
      unsubscribe()
    }
  }, [topic, tail, timestamps, combined, key])

  // Until the effect has reset the state for new options, show nothing from the old subscription.
  const current = state.key === key ? state : fresh(key)
  const connection: ConnectionState = conn.key === key ? conn.state : 'connecting'
  return { ...current, connection }
}
