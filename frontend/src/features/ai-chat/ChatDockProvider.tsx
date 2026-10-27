import { useCallback, useMemo, useRef, useState, type ReactNode } from 'react'
import { useChatSession, useRefreshSession } from './api.ts'
import { ChatDockContext, type ChatDock } from './useChatDock.ts'
import { useChatStream } from './useChatStream.ts'

/**
 * Owns the active chat session and its single `/topic/chat/{id}` subscription above the router, so a running
 * answer keeps streaming while the user navigates. Unmounting it (logout) unsubscribes.
 */
export function ChatDockProvider({ children }: { children: ReactNode }) {
  const [sessionId, setSessionId] = useState<string | null>(null)
  const [unread, setUnread] = useState(false)
  const viewers = useRef(new Map<string, number>())
  const refresh = useRefreshSession(sessionId ?? '')

  const onDone = useCallback(() => {
    if (sessionId && !viewers.current.get(sessionId)) setUnread(true)
    return refresh()
    // `refresh` changes identity every render; useChatStream reads the latest onDone through doneRef.
  }, [sessionId, refresh])
  const stream = useChatStream(sessionId, onDone)
  const detail = useChatSession(sessionId ?? '', sessionId !== null)
  const pendingCount = sessionId ? (detail.data?.messages.filter((m) => m.status === 'PENDING').length ?? 0) : 0

  const activeRef = useRef<string | null>(null)
  const open = useCallback((id: string) => {
    if (activeRef.current === id) return
    activeRef.current = id
    setUnread(false)
    setSessionId(id)
  }, [])
  const view = useCallback((id: string) => {
    viewers.current.set(id, (viewers.current.get(id) ?? 0) + 1)
    if (activeRef.current === id) setUnread(false)
    return () => {
      viewers.current.set(id, Math.max(0, (viewers.current.get(id) ?? 1) - 1))
    }
  }, [])
  const forget = useCallback((id: string) => {
    viewers.current.delete(id)
    if (activeRef.current !== id) return
    activeRef.current = null
    setUnread(false)
    setSessionId(null)
  }, [])

  const value = useMemo<ChatDock>(
    () => ({ sessionId, stream, unread, pendingCount, open, view, forget }),
    // The `stream` object is fresh each render, so the memo depends on its fields instead.
    [sessionId, stream.text, stream.activity, stream.running, stream.rateLimitWait, stream.connection, unread, pendingCount, open, view, forget],
  )
  return <ChatDockContext.Provider value={value}>{children}</ChatDockContext.Provider>
}
