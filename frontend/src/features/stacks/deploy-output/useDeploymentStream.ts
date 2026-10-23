import { useEffect, useRef, useState } from 'react'
import { subscribeToTopic, type ConnectionState } from '../../../shared/realtime/stomp.ts'

export type DeploymentResult = 'DONE' | 'FAILED'

export interface OutputLine {
  seq: number
  text: string
}

interface Frame {
  seq: number
  type: 'LINE' | 'STATUS'
  line?: string
  status?: DeploymentResult
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
export function useDeploymentStream(deploymentId: string, onFinished?: (result: DeploymentResult) => void) {
  const [lines, setLines] = useState<OutputLine[]>([])
  const [result, setResult] = useState<DeploymentResult | null>(null)
  const [connection, setConnection] = useState<ConnectionState>('connecting')
  const finished = useRef(onFinished)
  useEffect(() => {
    finished.current = onFinished
  })

  useEffect(() => {
    let lastSeq = -1
    let done = false
    const unsubscribe = subscribeToTopic(`/topic/deployments/${deploymentId}`, {
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
  }, [deploymentId])

  return { lines, result, connection }
}
