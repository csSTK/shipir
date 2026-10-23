import { useCallback, useEffect, useRef, useState } from 'react'
import { subscribeToTopic, type ConnectionState } from '../../shared/realtime/stomp.ts'

export interface Activity {
  tool: string
  state: 'running' | 'ok' | 'error'
}

interface Frame {
  type: 'TOKEN' | 'TOOL_CALL' | 'TOOL_RESULT' | 'PENDING_ACTION' | 'DONE'
  text?: string
  toolName?: string
  outcome?: string
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
 * Stored messages stay the source of truth: on DONE `onDone` refreshes them (awaited), then the live state clears.
 */
export function useChatStream(sessionId: string, onDone: () => Promise<unknown> | void) {
  const [text, setText] = useState('')
  const [activity, setActivity] = useState<Activity[]>([])
  const [running, setRunning] = useState(false)
  const [connection, setConnection] = useState<ConnectionState>('connecting')
  const doneRef = useRef(onDone)
  useEffect(() => {
    doneRef.current = onDone
  })

  const clear = useCallback(() => {
    setText('')
    setActivity([])
    setRunning(false)
  }, [])

  useEffect(() => {
    const unsubscribe = subscribeToTopic(`/topic/chat/${sessionId}`, {
      onState: setConnection,
      onMessage: (body) => {
        const frame = parseFrame(body)
        if (!frame) return
        if (frame.type === 'DONE') {
          void Promise.resolve(doneRef.current()).finally(clear)
          return
        }
        setRunning(true)
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
    return unsubscribe
  }, [sessionId, clear])

  return { text, activity, running, connection, start: () => setRunning(true), stop: clear }
}
