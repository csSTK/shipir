import { createContext, useContext } from 'react'
import type { ConnectionState } from '../../shared/realtime/stomp.ts'
import type { Activity } from './useChatStream.ts'

export interface ChatStream {
  text: string
  activity: Activity[]
  running: boolean
  rateLimitWait: number | null
  connection: ConnectionState
  start: () => void
  stop: () => void
}

export const IDLE_STREAM: ChatStream = {
  text: '',
  activity: [],
  running: false,
  rateLimitWait: null,
  connection: 'connecting',
  start: () => {},
  stop: () => {},
}

export interface ChatDock {
  /** The session whose run is followed (exactly one STOMP subscription), or null. */
  sessionId: string | null
  /** Live state of the active session's run. */
  stream: ChatStream
  /** Some chat's run finished while nobody was viewing it. Cleared per chat by `view`/`open`. */
  unread: boolean
  /** A run in a chat other than the active one is still going (it keeps being followed in the background). */
  runningElsewhere: boolean
  /** Number of write actions in all of the user's chats still waiting for Confirm/Reject. */
  pendingCount: number
  /** Makes a session the active one. A run still going in the previous one keeps being followed in the background. */
  open: (sessionId: string) => void
  /** Registers a visible view (page or dock) of a session and clears its unread flag. Returns the unregister function. */
  view: (sessionId: string) => () => void
  /** The session was deleted: unsubscribe if it is the active one. */
  forget: (sessionId: string) => void
}

export const ChatDockContext = createContext<ChatDock | null>(null)

export function useChatDock(): ChatDock {
  const dock = useContext(ChatDockContext)
  if (!dock) throw new Error('useChatDock needs a ChatDockProvider')
  return dock
}
