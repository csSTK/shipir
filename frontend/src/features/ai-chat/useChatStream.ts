import { useCallback, useEffect, useRef, useState } from 'react'
import { subscribeToTopic, type ConnectionState } from '../../shared/realtime/stomp.ts'

export interface Activity {
  tool: string
  state: 'running' | 'ok' | 'error'
}

interface Frame {
  type: 'TOKEN' | 'TOOL_CALL' | 'TOOL_RESULT' | 'PENDING_ACTION' | 'RATE_LIMIT_WAIT' | 'DONE'
  text?: string
  toolName?: string
  outcome?: string
  seconds?: number
}

function parseFrame(body: string): Frame | null {
  try {
    const f: unknown = JSON.parse(body)
    if (!f || typeof f !== 'object' || !('type' in f) || typeof f.type !== 'string') return null
    return f as Frame
  } catch {
    return null
  }
}

/**
 * Live view of the assistant's run in one chat: streamed text and tool activity.
 * `null` means no session: nothing is subscribed. Changing the session unsubscribes and clears the live state.
 * Stored messages stay the source of truth: on DONE `onDone` refreshes them (awaited), then the live state clears.
 */
export function useChatStream(sessionId: string | null, onDone: () => Promise<unknown> | void) {
  const [text, setText] = useState('')
  const [activity, setActivity] = useState<Activity[]>([])
  const [running, setRunning] = useState(false)
  const [rateLimitWait, setRateLimitWait] = useState<number | null>(null)
  const [waitFrames, setWaitFrames] = useState(0)
  const [connection, setConnection] = useState<ConnectionState>('connecting')
  const doneRef = useRef(onDone)
  const doneCountRef = useRef(0)
  useEffect(() => {
    doneRef.current = onDone
  })

  const clear = useCallback(() => {
    setText('')
    setActivity([])
    setRunning(false)
    setRateLimitWait(null)
  }, [])

  // Local countdown while the backend waits out a provider rate limit; it stays at 0 until the run continues.
  const waiting = rateLimitWait !== null
  useEffect(() => {
    if (!waiting) return
    const timer = setInterval(() => setRateLimitWait((s) => (s === null ? s : Math.max(0, s - 1))), 1000)
    return () => clearInterval(timer)
  }, [waiting, waitFrames])

  useEffect(() => {
    if (sessionId === null) return
    let current = true
    const unsubscribe = subscribeToTopic(`/topic/chat/${sessionId}`, {
      onState: setConnection,
      onMessage: (body) => {
        const frame = parseFrame(body)
        if (!frame) return
        if (frame.type === 'DONE') {
          doneCountRef.current += 1
          // Only clear while this session's subscription is still the active one: a late refresh must not wipe the next session.
          void Promise.resolve(doneRef.current()).finally(() => {
            if (current) clear()
          })
          return
        }
        setRunning(true)
        if (frame.type === 'RATE_LIMIT_WAIT') {
          const seconds = Number(frame.seconds)
          setWaitFrames((n) => n + 1)
          setRateLimitWait(Number.isFinite(seconds) ? Math.max(1, Math.ceil(seconds)) : 1)
          return
        }
        setRateLimitWait(null)
        if (frame.type === 'TOKEN') setText((prev) => prev + (frame.text ?? ''))
        else if (frame.type === 'TOOL_CALL') setActivity((prev) => [...prev, { tool: frame.toolName ?? '', state: 'running' }])
        else if (frame.type === 'TOOL_RESULT') {
          const state = frame.outcome === 'ERROR' ? 'error' : 'ok'
          setActivity((prev) => {
            const i = prev.findLastIndex((a) => a.tool === frame.toolName && a.state === 'running')
            return i < 0 ? prev : prev.map((a, j) => (j === i ? { ...a, state } : a))
          })
        }
      },
    })
    return () => {
      current = false
      unsubscribe()
      clear()
      setConnection('connecting')
    }
  }, [sessionId, clear])

  // Number of DONE frames seen so far: a caller that checks the server for a running run compares it before and after
  // to tell that the run ended while its answer was still on the way.
  const doneCount = useCallback(() => doneCountRef.current, [])
  return { text, activity, running, rateLimitWait, connection, start: () => setRunning(true), stop: clear, doneCount }
}
