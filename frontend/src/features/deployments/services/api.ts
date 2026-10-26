import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiFetch } from '../../../shared/api/client.ts'
import type { DeploymentRun } from '../api.ts'

export interface ServicesInfo {
  /** Service names of the Compose file, including services behind a profile. */
  services: string[]
  /** Profile names the Compose file defines. */
  profiles: string[]
}

export type ServiceAction = 'restart' | 'pull' | 'up'

const base = (hostId: string, deploymentId: string) => `/hosts/${hostId}/managed-deployments/${deploymentId}`
const deploymentKey = (hostId: string) => ['hosts', hostId, 'managed-deployments'] as const

/** Services and profiles of the stored Compose file (`GET .../services`, ADMIN/OPERATOR only). */
export function useServices(hostId: string, deploymentId: string, enabled: boolean) {
  return useQuery({
    queryKey: [...deploymentKey(hostId), deploymentId, 'services'],
    queryFn: () => apiFetch<ServicesInfo>(`${base(hostId, deploymentId)}/services`),
    enabled,
    retry: false,
  })
}

/** Replaces the active Compose profiles (`PUT .../profiles`); an empty list clears them. */
export function useSetProfiles(hostId: string, deploymentId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (profiles: string[]) => apiFetch<unknown>(`${base(hostId, deploymentId)}/profiles`, { method: 'PUT', body: { profiles } }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: deploymentKey(hostId) }),
  })
}

/** Starts a per-service run (`POST .../services/{service}/{action}`, 202 with `runId`, 409 when busy). */
export function useServiceAction(hostId: string, deploymentId: string) {
  return useMutation({
    mutationFn: ({ service, action }: { service: string; action: ServiceAction }) =>
      apiFetch<DeploymentRun>(`${base(hostId, deploymentId)}/services/${encodeURIComponent(service)}/${action}`, { method: 'POST' }),
  })
}

/** Starts all services of the deployment without recreating them (`POST .../start`, 202). */
export function useStartAll(hostId: string, deploymentId: string) {
  return useMutation({
    mutationFn: () => apiFetch<DeploymentRun>(`${base(hostId, deploymentId)}/start`, { method: 'POST' }),
  })
}
