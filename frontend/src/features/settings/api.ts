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
