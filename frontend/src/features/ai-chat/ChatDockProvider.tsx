import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { subscribeToTopic } from '../../shared/realtime/stomp.ts'
import { usePendingActionCount, useRefreshSession } from './api.ts'
import { ChatDockContext, type ChatDock } from './useChatDock.ts'
import { useChatStream } from './useChatStream.ts'

/** Follows a run in a chat that is no longer the active one, only to learn when it ends. Renders nothing. */
function BackgroundRun({ sessionId, onDone }: { sessionId: string; onDone: (id: string) => void }) {
  const doneRef = useRef(onDone)
  useEffect(() => {
    doneRef.current = onDone
  })
  useEffect(
    () =>
      subscribeToTopic(`/topic/chat/${sessionId}`, {
        onState: () => {},
        onMessage: (body) => {
          try {
            const f: unknown = JSON.parse(body)
            if (f && typeof f === 'object' && 'type' in f && f.type === 'DONE') doneRef.current(sessionId)
          } catch {
            // ignore malformed frames
          }
        },
      }),
    [sessionId],
  )
  return null
}

/**
 * Owns the active chat session and its single `/topic/chat/{id}` subscription above the router, so a running
 * answer keeps streaming while the user navigates. Switching away from a running chat keeps a lightweight
 * subscription on it until it ends, so its finished answer and pending actions are still reported.
 * Unmounting it (logout) unsubscribes.
 */
export function ChatDockProvider({ children }: { children: ReactNode }) {
  const client = useQueryClient()
  const [sessionId, setSessionId] = useState<string | null>(null)
  const [unreadIds, setUnreadIds] = useState<ReadonlySet<string>>(new Set())
  const [background, setBackground] = useState<readonly string[]>([])
  const viewers = useRef(new Map<string, number>())
  const refresh = useRefreshSession(sessionId ?? '')
  const pending = usePendingActionCount()

  const markUnread = useCallback((id: string) => {
    if (!viewers.current.get(id)) setUnreadIds((prev) => new Set(prev).add(id))
  }, [])
  const clearUnread = useCallback((id: string) => {
    setUnreadIds((prev) => {
      if (!prev.has(id)) return prev
      const next = new Set(prev)
      next.delete(id)
      return next
    })
  }, [])

  const onDone = useCallback(() => {
    if (sessionId) markUnread(sessionId)
    return refresh()
    // `refresh` changes identity every render; useChatStream reads the latest onDone through doneRef.
  }, [sessionId, markUnread, refresh])
  const { text, activity, running, rateLimitWait, connection, start, stop } = useChatStream(sessionId, onDone)
  // `start` is a fresh closure each render (it only sets state), so the memo below keys on the data fields.
  const startRef = useRef(start)
  useEffect(() => {
    startRef.current = start
  })

  const runningRef = useRef(false)
  useEffect(() => {
    runningRef.current = running
  })

  const onBackgroundDone = useCallback(
    (id: string) => {
      setBackground((prev) => prev.filter((b) => b !== id))
      markUnread(id)
      void client.invalidateQueries({ queryKey: ['ai', 'session', id] })
      void client.invalidateQueries({ queryKey: ['ai', 'sessions'] })
    },
    [client, markUnread],
  )

  const activeRef = useRef<string | null>(null)
  const open = useCallback(
    (id: string) => {
      const previous = activeRef.current
      if (previous === id) return
      activeRef.current = id
      // A run still going in the chat being left stays followed; coming back to a followed chat takes over again.
      setBackground((prev) => {
        const next = prev.filter((b) => b !== id)
        return previous && runningRef.current ? [...next, previous] : next
      })
      clearUnread(id)
      setSessionId(id)
    },
    [clearUnread],
  )
  const view = useCallback(
    (id: string) => {
      viewers.current.set(id, (viewers.current.get(id) ?? 0) + 1)
      clearUnread(id)
      return () => {
        viewers.current.set(id, Math.max(0, (viewers.current.get(id) ?? 1) - 1))
      }
    },
    [clearUnread],
  )
  const forget = useCallback(
    (id: string) => {
      viewers.current.delete(id)
      clearUnread(id)
      setBackground((prev) => prev.filter((b) => b !== id))
      if (activeRef.current !== id) return
      activeRef.current = null
      setSessionId(null)
    },
    [clearUnread],
  )

  const unread = unreadIds.size > 0
  const runningElsewhere = background.length > 0
  const pendingCount = pending.data ?? 0

  const value = useMemo<ChatDock>(
    () => ({
      sessionId,
      stream: { text, activity, running, rateLimitWait, connection, start: () => startRef.current(), stop },
      unread,
      runningElsewhere,
      pendingCount,
      open,
      view,
      forget,
    }),
    [sessionId, text, activity, running, rateLimitWait, connection, stop, unread, runningElsewhere, pendingCount, open, view, forget],
  )
  return (
    <ChatDockContext.Provider value={value}>
      {background.map((id) => (
        <BackgroundRun key={id} sessionId={id} onDone={onBackgroundDone} />
      ))}
      {children}
    </ChatDockContext.Provider>
  )
}
