import { useEffect, useRef, useState } from 'react'
import { subscribeToTopic, type ConnectionState } from '../../../shared/realtime/stomp.ts'

export type RunResult = 'DONE' | 'FAILED'

export interface OutputLine {
  seq: number
  text: string
}

interface Frame {
  seq: number
  type: 'LINE' | 'STATUS'
  line?: string
  status?: RunResult
}

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

/** Live output of one deployment. Frames are de-duplicated by `seq` (the server replays on reconnect). */
export function useRunStream(runId: string, onFinished?: (result: RunResult) => void) {
  const [lines, setLines] = useState<OutputLine[]>([])
  const [result, setResult] = useState<RunResult | null>(null)
  const [connection, setConnection] = useState<ConnectionState>('connecting')
  const [seenRunId, setSeenRunId] = useState(runId)
  if (seenRunId !== runId) {
    // A new run starts empty: drop the previous run's lines and result while rendering, before the new subscription.
    setSeenRunId(runId)
    setLines([])
    setResult(null)
    setConnection('connecting')
  }
  const finished = useRef(onFinished)
  useEffect(() => {
    finished.current = onFinished
  })

  useEffect(() => {
    let lastSeq = -1
    let done = false
    const unsubscribe = subscribeToTopic(`/topic/deployments/${runId}`, {
      onState: (state) => {
        if (!done) setConnection(state)
      },
      onMessage: (body) => {
        const frame = parseFrame(body)
        if (!frame || frame.seq <= lastSeq) return
        lastSeq = frame.seq
        if (frame.type === 'LINE') {
          const text = frame.line ?? ''
          setLines((prev) => [...prev, { seq: frame.seq, text }])
        } else if (frame.status && !done) {
          done = true
          setResult(frame.status)
          finished.current?.(frame.status)
          unsubscribe() // the stream is complete: stop reconnecting
        }
      },
    })
    return () => {
      done = true
      unsubscribe()
    }
  }, [runId])

  return { lines, result, connection }
}
