import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiFetch } from '../../shared/api/client.ts'

export type WebhookEventType = 'HOST_DOWN' | 'HOST_UP'

export const WEBHOOK_EVENT_LABELS: Record<WebhookEventType, string> = {
  HOST_DOWN: 'Host down',
  HOST_UP: 'Host up',
}

/** The full URL is never returned by the API, only a hint. */
export interface Webhook {
  id: string
  name: string
  urlHint: string
  eventTypes: WebhookEventType[]
  enabled: boolean
  lastDeliveryAt: string | null
  /** `SUCCESS`, `HTTP_<code>`, `TIMEOUT` or `FAILED`; null when never delivered. */
  lastDeliveryStatus: string | null
}

/** `url` is omitted on update to keep the stored one. */
export interface WebhookInput {
  name: string
  url?: string
  eventTypes: WebhookEventType[]
  enabled: boolean
}

/** `status` is `SUCCESS`, `HTTP_<code>`, `TIMEOUT` or `FAILED`. */
export interface WebhookTestResult {
  ok: boolean
  status: string
}

export const WEBHOOKS_KEY = ['notification-webhooks'] as const
const BASE = '/notifications/webhooks'

export function useWebhooks() {
  return useQuery({ queryKey: WEBHOOKS_KEY, queryFn: () => apiFetch<Webhook[]>(BASE) })
}

function useInvalidate() {
  const queryClient = useQueryClient()
  return () => queryClient.invalidateQueries({ queryKey: WEBHOOKS_KEY })
}

export function useCreateWebhook() {
  const invalidate = useInvalidate()
  return useMutation({
    mutationFn: (input: WebhookInput) => apiFetch<Webhook>(BASE, { method: 'POST', body: input }),
    onSuccess: () => void invalidate(),
  })
}

export function useUpdateWebhook() {
  const invalidate = useInvalidate()
  return useMutation({
    mutationFn: ({ id, input }: { id: string; input: WebhookInput }) =>
      apiFetch<Webhook>(`${BASE}/${id}`, { method: 'PUT', body: input }),
    onSuccess: () => void invalidate(),
  })
}

export function useDeleteWebhook() {
  const invalidate = useInvalidate()
  return useMutation({
    mutationFn: (id: string) => apiFetch<void>(`${BASE}/${id}`, { method: 'DELETE' }),
    onSuccess: () => void invalidate(),
  })
}

export function useTestWebhook() {
  const invalidate = useInvalidate()
  return useMutation({
    mutationFn: (id: string) => apiFetch<WebhookTestResult>(`${BASE}/${id}/test`, { method: 'POST' }),
    onSettled: () => void invalidate(),
  })
}

/** Retention periods in days, 1 to 3650. Purge runs once a day on the server. */
export interface RetentionSettings {
  auditLogDays: number
  notificationDays: number
}

export const RETENTION_KEY = ['settings-retention'] as const
const RETENTION_PATH = '/settings/retention'

export function useRetentionSettings() {
  return useQuery({ queryKey: RETENTION_KEY, queryFn: () => apiFetch<RetentionSettings>(RETENTION_PATH) })
}

export function useSaveRetentionSettings() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (input: RetentionSettings) =>
      apiFetch<RetentionSettings>(RETENTION_PATH, { method: 'PUT', body: input }),
    onSuccess: (saved) => queryClient.setQueryData(RETENTION_KEY, saved),
  })
}

export const AUDIT_ACTION_LABELS: Record<string, string> = {
  LOGIN: 'Login',
  LOGOUT: 'Logout',
  PASSWORD_CHANGE: 'Password changed',
  PASSWORD_RESET_CONFIRM: 'Password reset',
  HOST_CREATE: 'Host created',
  HOST_RENAME: 'Host renamed',
  HOST_DELETE: 'Host deleted',
  WEBHOOK_CREATE: 'Webhook created',
  WEBHOOK_UPDATE: 'Webhook updated',
  WEBHOOK_DELETE: 'Webhook deleted',
  RETENTION_UPDATE: 'Retention updated',
  RETENTION_PURGE: 'Retention purge',
}

export interface AuditLogEntry {
  id: string
  occurredAt: string
  username: string
  action: string
  outcome: 'SUCCESS' | 'FAILURE'
  targetType: string | null
  targetId: string | null
  details: Record<string, unknown> | null
}

export interface AuditLogPageData {
  items: AuditLogEntry[]
  page: number
  size: number
  totalElements: number
}

/** `from`/`to` are inclusive ISO-8601 instants; `page` is zero-based. */
export interface AuditLogQuery {
  page: number
  size: number
  action?: string
  username?: string
  from?: string
  to?: string
}

export function useAuditLog(query: AuditLogQuery) {
  const params = new URLSearchParams({ page: String(query.page), size: String(query.size) })
  for (const key of ['action', 'username', 'from', 'to'] as const) {
    if (query[key]) params.set(key, query[key])
  }
  const path = `/audit-log?${params}`
  return useQuery({
    queryKey: ['audit-log', path],
    queryFn: () => apiFetch<AuditLogPageData>(path),
    placeholderData: (previous) => previous,
  })
}
