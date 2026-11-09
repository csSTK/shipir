import { useMutation, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query'
import { apiFetch } from '../../shared/api/client.ts'

export interface ChatSession {
  id: string
  title: string
  sharedWithAdmin: boolean
  createdAt: string
  updatedAt: string
  /** Only filled in the ADMIN view of shared sessions. */
  ownerUsername: string | null
}

export type ChatRole = 'USER' | 'ASSISTANT' | 'TOOL'
export type ActionStatus = 'PENDING' | 'CONFIRMED' | 'REJECTED'

export interface ToolCall {
  id: string
  name: string
  /** Object or JSON text; `env` values are shown as `<hidden>`. */
  arguments?: unknown
  description?: string
  /** The assistant had tool output (names, YAML, notifications) in view when it proposed this. */
  fromToolContent?: boolean
}

export interface ChatMessage {
  id: string
  seq: number
  role: ChatRole
  content: string | null
  toolCalls: ToolCall[] | null
  toolCallId: string | null
  /** Set for write actions only. */
  status: ActionStatus | null
  createdAt: string
}

export interface ChatSessionDetail {
  session: ChatSession
  messages: ChatMessage[]
  /** An assistant run is active for this session right now. */
  running: boolean
}

/** What the user is looking at; the server drops ids it cannot resolve for this user. */
export interface ViewContext {
  page: 'OVERVIEW' | 'HOST' | 'DEPLOYMENT' | 'CONTAINER' | 'IMAGES' | 'NETWORKS' | 'VOLUMES' | 'SETTINGS' | 'OTHER'
  hostId?: string
  deploymentId?: string
  containerId?: string
}
export type ViewPage = ViewContext['page']

export interface ActionResult {
  messageId: string
  status: ActionStatus
  error: boolean
  result: string | null
  runId?: string
}

const SESSIONS_KEY = ['ai', 'sessions'] as const
const sessionKey = (id: string) => ['ai', 'session', id] as const

export function useChatSessions() {
  return useQuery({
    queryKey: SESSIONS_KEY,
    queryFn: () => apiFetch<{ items: ChatSession[] }>('/ai/sessions?page=0&size=100'),
  })
}

/** Always fetches a session from the server (never serves the cache) and stores it under the session's query key. */
export function fetchChatSession(client: QueryClient, id: string) {
  return client.fetchQuery({ queryKey: sessionKey(id), queryFn: () => apiFetch<ChatSessionDetail>(`/ai/sessions/${id}`), staleTime: 0 })
}

/** Write actions waiting for Confirm/Reject across all of the user's chats; refreshed with the session list. */
export function usePendingActionCount(enabled = true) {
  return useQuery({
    queryKey: [...SESSIONS_KEY, 'pending-count'],
    queryFn: () => apiFetch<{ count: number }>('/ai/sessions/pending-count'),
    select: (d) => d.count,
    enabled,
  })
}

export function useChatSession(id: string, enabled = true) {
  return useQuery({
    queryKey: sessionKey(id),
    queryFn: () => apiFetch<ChatSessionDetail>(`/ai/sessions/${id}`),
    enabled,
  })
}

/** Refetches a session and the session list (after a run ended or an action was resolved). */
export function useRefreshSession(id: string) {
  const client = useQueryClient()
  return () =>
    Promise.all([
      client.invalidateQueries({ queryKey: sessionKey(id) }),
      client.invalidateQueries({ queryKey: SESSIONS_KEY }),
    ])
}

export function useCreateChatSession() {
  const client = useQueryClient()
  return useMutation({
    mutationFn: () => apiFetch<ChatSession>('/ai/sessions', { method: 'POST', body: {} }),
    onSuccess: () => client.invalidateQueries({ queryKey: SESSIONS_KEY }),
  })
}

export function useUpdateChatSession(id: string) {
  const refresh = useRefreshSession(id)
  return useMutation({
    mutationFn: (body: { title?: string; sharedWithAdmin?: boolean }) =>
      apiFetch<ChatSession>(`/ai/sessions/${id}`, { method: 'PATCH', body }),
    onSuccess: refresh,
  })
}

export function useDeleteChatSession(id: string) {
  const client = useQueryClient()
  return useMutation({
    mutationFn: () => apiFetch<void>(`/ai/sessions/${id}`, { method: 'DELETE' }),
    onSuccess: () => {
      client.removeQueries({ queryKey: sessionKey(id) })
      return client.invalidateQueries({ queryKey: SESSIONS_KEY })
    },
  })
}

export function useSendChatMessage(id: string) {
  return useMutation({
    mutationFn: ({ content, context }: { content: string; context?: ViewContext }) =>
      apiFetch<{ runId: string }>(`/ai/sessions/${id}/messages`, { method: 'POST', body: context ? { content, context } : { content } }),
  })
}

export function useResolveAction(id: string) {
  return useMutation({
    mutationFn: ({ messageId, decision }: { messageId: string; decision: 'confirm' | 'reject' }) =>
      apiFetch<ActionResult>(`/ai/sessions/${id}/actions/${messageId}/${decision}`, { method: 'POST' }),
  })
}
