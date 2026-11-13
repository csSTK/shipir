import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiFetch } from '../../shared/api/client.ts'
import { isEventStreamLive } from '../../shared/realtime/useHostEvents.ts'
import { CURRENT_USER_KEY } from '../auth/api.ts'

export type ConnectionType = 'LOCAL' | 'TCP'

export interface Host {
  id: string
  name: string
  connectionType: ConnectionType
  host: string | null
  port: number | null
  tls: boolean
  createdAt: string
  /** Sorted, lowercase environment tags. */
  tags: string[]
}

/** Body of create and connection test. LOCAL sends none of host/port/PEMs. */
export interface HostRequest {
  name?: string
  connectionType: ConnectionType
  host?: string
  port?: number
  tlsCaPem?: string
  tlsCertPem?: string
  tlsKeyPem?: string
}

export type ConnectionErrorCode =
  | 'UNREACHABLE'
  | 'TIMEOUT'
  | 'TLS_ERROR'
  | 'PERMISSION_DENIED'
  | 'INVALID_TLS_MATERIAL'

export interface ConnectionTestResult {
  ok: boolean
  dockerVersion: string | null
  errorCode: ConnectionErrorCode | null
  message: string | null
}

export type HostStatus = 'UP' | 'DOWN'

export interface ContainerCounts {
  running: number
  stopped: number
  healthy: number
  unhealthy: number
}

/** One row of `GET /api/hosts/overview`. All stats are null while the status is DOWN; host facts and images/volumes are also null for a user who sees the host only through deployment grants. */
export interface HostOverview {
  id: string
  name: string
  connectionType: ConnectionType
  endpoint: string
  status: HostStatus
  /** Error code (e.g. TIMEOUT) while DOWN, otherwise null. */
  error: string | null
  dockerVersion: string | null
  cpus: number | null
  memoryBytes: number | null
  containers: ContainerCounts | null
  images: number | null
  volumes: number | null
  /** Sorted, lowercase environment tags. */
  tags: string[]
}

export const HOSTS_KEY = ['hosts'] as const
/** Shared with the overview hook (shipr-406.11). */
export const HOSTS_OVERVIEW_KEY = ['hosts', 'overview'] as const

export function useHosts() {
  return useQuery({ queryKey: HOSTS_KEY, queryFn: () => apiFetch<Host[]>('/hosts') })
}

const OVERVIEW_REFRESH_MS = 30_000
/** Polling fallback while the live event stream is connected. */
const OVERVIEW_REFRESH_LIVE_MS = 120_000

/** Live status and stats of every environment; refreshes every 30 s and when the window regains focus. */
export function useHostsOverview() {
  return useQuery({
    queryKey: HOSTS_OVERVIEW_KEY,
    queryFn: () => apiFetch<HostOverview[]>('/hosts/overview'),
    refetchInterval: () => (isEventStreamLive() ? OVERVIEW_REFRESH_LIVE_MS : OVERVIEW_REFRESH_MS),
    refetchOnWindowFocus: true,
  })
}

export function useInvalidateHostData() {
  const queryClient = useQueryClient()
  return () =>
    Promise.all([
      queryClient.invalidateQueries({ queryKey: HOSTS_KEY }),
      queryClient.invalidateQueries({ queryKey: HOSTS_OVERVIEW_KEY }),
      // The environment count feeds the setup gate (`/auth/me`).
      queryClient.invalidateQueries({ queryKey: CURRENT_USER_KEY }),
    ])
}

export function useCreateHost() {
  const invalidate = useInvalidateHostData()
  return useMutation({
    mutationFn: (request: HostRequest) => apiFetch<Host>('/hosts', { method: 'POST', body: request }),
    onSuccess: () => void invalidate(),
  })
}

export function useTestConnection() {
  return useMutation({
    mutationFn: (request: HostRequest) =>
      apiFetch<ConnectionTestResult>('/hosts/connection-test', { method: 'POST', body: request }),
  })
}

export function useRenameHost() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, name }: { id: string; name: string }) =>
      apiFetch<Host>(`/hosts/${id}`, { method: 'PATCH', body: { name } }),
    onSuccess: () =>
      void Promise.all([
        queryClient.invalidateQueries({ queryKey: HOSTS_KEY }),
        queryClient.invalidateQueries({ queryKey: HOSTS_OVERVIEW_KEY }),
      ]),
  })
}

/** `DELETE /api/hosts/{id}`: `leftovers` lists what a requested proxy removal could not remove on the Docker host. */
export interface HostDeleteResult {
  leftovers: string[]
}

/**
 * Deletes an environment. With `removeProxy` the managed proxy is removed from the Docker host first (best effort).
 * When something is left behind the lists are not refreshed yet, so the dialog can still show it: the caller invalidates
 * with `useInvalidateHostData` once the user has seen it.
 */
export function useDeleteHost() {
  const invalidate = useInvalidateHostData()
  return useMutation({
    mutationFn: ({ id, removeProxy }: { id: string; removeProxy: boolean }) =>
      apiFetch<HostDeleteResult | undefined>(`/hosts/${id}${removeProxy ? '?removeProxy=true' : ''}`, { method: 'DELETE' }),
    onSuccess: (result) => {
      if (!result?.leftovers?.length) void invalidate()
    },
  })
}

/** Replaces the whole tag set of an environment (ADMIN). */
export function useSetHostTags() {
  const invalidate = useInvalidateHostData()
  return useMutation({
    mutationFn: ({ id, tags }: { id: string; tags: string[] }) =>
      apiFetch<Host>(`/hosts/${id}/tags`, { method: 'PUT', body: { tags } }),
    onSuccess: () => void invalidate(),
  })
}
