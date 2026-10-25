import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiFetch } from '../../../shared/api/client.ts'

/** `GET .../webhook`: state only, never the token. */
export interface WebhookStatus {
  enabled: boolean
  createdAt: string | null
  rotatedAt: string | null
}

/** Result of enabling or rotating: `token` and `path` (which contains it) are returned exactly once. */
export interface WebhookIssued extends WebhookStatus {
  token: string
  path: string
}

const base = (hostId: string, deploymentId: string) => `/hosts/${hostId}/managed-deployments/${deploymentId}/webhook`
const statusKey = (hostId: string, deploymentId: string) => ['hosts', hostId, 'managed-deployments', deploymentId, 'webhook'] as const

export function useWebhookStatus(hostId: string, deploymentId: string, enabled: boolean) {
  return useQuery({
    queryKey: statusKey(hostId, deploymentId),
    queryFn: () => apiFetch<WebhookStatus>(base(hostId, deploymentId)),
    enabled,
    retry: false,
  })
}

function useRefreshStatus(hostId: string, deploymentId: string) {
  const queryClient = useQueryClient()
  return () => queryClient.invalidateQueries({ queryKey: statusKey(hostId, deploymentId) })
}

/** Enable (PUT) or rotate (POST): the result carries the secret, so it never stays in the mutation cache (`gcTime: 0`). */
export function useIssueWebhook(hostId: string, deploymentId: string, action: 'enable' | 'rotate') {
  const refresh = useRefreshStatus(hostId, deploymentId)
  return useMutation({
    mutationFn: () =>
      action === 'enable'
        ? apiFetch<WebhookIssued>(base(hostId, deploymentId), { method: 'PUT' })
        : apiFetch<WebhookIssued>(`${base(hostId, deploymentId)}/rotate`, { method: 'POST' }),
    gcTime: 0,
    onSettled: refresh,
  })
}

export function useDisableWebhook(hostId: string, deploymentId: string) {
  const refresh = useRefreshStatus(hostId, deploymentId)
  return useMutation({
    mutationFn: () => apiFetch<void>(base(hostId, deploymentId), { method: 'DELETE' }),
    onSettled: refresh,
  })
}
