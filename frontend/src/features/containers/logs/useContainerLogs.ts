import { useEffect, useState } from 'react'
import { subscribeToTopic, type ConnectionState } from '../../../shared/realtime/stomp.ts'

export type TailOption = '100' | '200' | '500' | '1000' | 'all'
export type LogEnd = { status: 'ENDED' } | { status: 'ERROR'; code: string }

export interface LogLine {
  seq: number
  stream: 'STDOUT' | 'STDERR'
  text: string
  timestamp?: string
}

interface Frame {
  seq: number
  type: 'LINE' | 'STATUS'
  stream?: 'STDOUT' | 'STDERR'
  line?: string
  status?: 'ENDED' | 'ERROR'
  timestamp?: string
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

interface LogState {
  key: string
  lines: LogLine[]
  end: LogEnd | null
}

/**
 * Live log of one container. Options are fixed per subscription, so changing `tail` or `timestamps`
 * resubscribes and starts with an empty view. Frames are de-duplicated by `seq` (the server replays).
 */
export function useContainerLogs(hostId: string, containerId: string, tail: TailOption, timestamps: boolean) {
  const key = `${hostId}/${containerId}/${tail}/${timestamps}`
  const [state, setState] = useState<LogState>({ key, lines: [], end: null })
  const [connection, setConnection] = useState<ConnectionState>('connecting')

  useEffect(() => {
    let lastSeq = -1
    let done = false
    setState({ key, lines: [], end: null })
    setConnection('connecting')
    const unsubscribe = subscribeToTopic(
      `/topic/hosts/${hostId}/containers/${containerId}/logs`,
      {
        onState: (s) => {
          if (!done) setConnection(s)
        },
        onMessage: (body) => {
          const frame = parseFrame(body)
          if (!frame || done || frame.seq <= lastSeq) return
          lastSeq = frame.seq
          if (frame.type === 'LINE') {
            const line: LogLine = {
              seq: frame.seq,
              stream: frame.stream === 'STDERR' ? 'STDERR' : 'STDOUT',
              text: frame.line ?? '',
              timestamp: frame.timestamp,
            }
            setState((prev) => ({ ...prev, lines: [...prev.lines, line].slice(-MAX_LOG_LINES) }))
          } else if (frame.status === 'ENDED' || frame.status === 'ERROR') {
            done = true
            const end: LogEnd = frame.status === 'ENDED' ? { status: 'ENDED' } : { status: 'ERROR', code: frame.line ?? 'UNKNOWN' }
            setState((prev) => ({ ...prev, end }))
            unsubscribe() // stream is complete: stop reconnecting
          }
        },
      },
      { headers: { tail, timestamps: String(timestamps) } },
    )
    return () => {
      done = true
      unsubscribe()
    }
  }, [hostId, containerId, tail, timestamps, key])

  // Until the effect has reset the state for new options, show nothing from the old subscription.
  const current = state.key === key
  return { lines: current ? state.lines : [], end: current ? state.end : null, connection }
}
