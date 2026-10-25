import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiFetch } from '../../../shared/api/client.ts'

export type UptimeState = 'UP' | 'DOWN' | 'UNKNOWN'

export interface UptimeResult {
  checkedAt: string
  ok: boolean
  statusCode: number | null
  latencyMs: number | null
  errorCode: string | null
}

export interface UptimeCheck {
  id: string
  name: string
  url: string
  expectedStatus: number | null
  intervalSeconds: number
  timeoutSeconds: number
  enabled: boolean
  lastStatus: UptimeState
  lastCheckedAt: string | null
  lastChangeAt: string | null
  consecutiveFailures: number
  /** Newest first. */
  recentResults: UptimeResult[]
}

export interface UptimeCheckInput {
  name: string
  url: string
  expectedStatus: number | null
  intervalSeconds: number
  timeoutSeconds: number
  enabled: boolean
}

const base = (hostId: string, deploymentId: string) => `/hosts/${hostId}/managed-deployments/${deploymentId}/uptime-checks`
const key = (hostId: string, deploymentId: string) => ['hosts', hostId, 'managed-deployments', deploymentId, 'uptime-checks'] as const

export function useUptimeChecks(hostId: string, deploymentId: string, enabled: boolean) {
  return useQuery({
    queryKey: key(hostId, deploymentId),
    queryFn: () => apiFetch<UptimeCheck[]>(base(hostId, deploymentId)),
    enabled,
    retry: false,
  })
}

export function useSaveUptimeCheck(hostId: string, deploymentId: string, checkId: string | null) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (input: UptimeCheckInput) =>
      checkId
        ? apiFetch<UptimeCheck>(`${base(hostId, deploymentId)}/${checkId}`, { method: 'PUT', body: input })
        : apiFetch<UptimeCheck>(base(hostId, deploymentId), { method: 'POST', body: input }),
    onSettled: () => queryClient.invalidateQueries({ queryKey: key(hostId, deploymentId) }),
  })
}

export function useDeleteUptimeCheck(hostId: string, deploymentId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (checkId: string) => apiFetch<void>(`${base(hostId, deploymentId)}/${checkId}`, { method: 'DELETE' }),
    onSettled: () => queryClient.invalidateQueries({ queryKey: key(hostId, deploymentId) }),
  })
}
