import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiFetch } from '../../../shared/api/client.ts'
import type { CertMode } from '../api.ts'

export type BackendScheme = 'HTTP' | 'HTTPS' | 'H2C'

/** One published route (`GET /hosts/{id}/proxy/routes`). */
export interface ProxyRoute {
  id: string
  hostname: string
  aliases: string[]
  containerName: string
  targetPort: number
  backendScheme: BackendScheme
  pathPrefix: string | null
  entrypoint: string | null
  certMode: CertMode
  certificateId: string | null
  redirectHttps: boolean
  basicAuthUsers: string[]
  responseHeaders: Record<string, string>
  /** True when any setting beyond hostname, container and port differs from the simple default. */
  advancedActive: boolean
  /** Null when the container no longer exists. */
  containerState: string | null
}

/** A container that can be published. `suggestedPort` is set only when exactly one port is exposed. */
export interface RouteCandidate {
  containerName: string
  image: string
  state: string
  ports: number[]
  suggestedPort: number | null
  published: boolean
}

export type CertificateStatusName = 'ISSUED' | 'PENDING' | 'FAILED' | 'INTERNAL' | 'CUSTOM'

export interface RouteCertificateStatus {
  hostname: string
  status: CertificateStatusName
  notAfter: string | null
  /** Short sanitized log line, only for FAILED. Render as plain text. */
  lastError: string | null
}

/** Publish or update. On update, `null`/missing containerName, port, certMode and basicAuth keep the stored value; every other field is replaced as sent. */
export interface RouteRequest {
  hostname: string
  containerName?: string
  port?: number
  aliases?: string[]
  backendScheme?: BackendScheme
  pathPrefix?: string | null
  entrypoint?: string | null
  certMode?: CertMode
  certificateId?: string | null
  redirectHttps?: boolean
  /** Username -> plain password, sent once and stored as a hash. Omit to keep the stored users; an empty password keeps that user's hash. */
  basicAuth?: Record<string, string>
  responseHeaders?: Record<string, string>
}

const routesKey = (hostId: string) => ['hosts', hostId, 'proxy', 'routes'] as const
const base = (hostId: string) => `/hosts/${hostId}/proxy/routes`

export function useRoutes(hostId: string, enabled = true) {
  return useQuery({ queryKey: routesKey(hostId), queryFn: () => apiFetch<ProxyRoute[]>(base(hostId)), enabled })
}

export function useRouteCandidates(hostId: string, enabled = true) {
  return useQuery({
    queryKey: [...routesKey(hostId), 'candidates'],
    queryFn: () => apiFetch<RouteCandidate[]>(`${base(hostId)}/candidates`),
    enabled,
  })
}

const POLL_MS = 10_000

/** Certificate status per hostname; polls while a certificate is still pending. */
export function useRouteStatuses(hostId: string) {
  return useQuery({
    queryKey: [...routesKey(hostId), 'status'],
    queryFn: () => apiFetch<RouteCertificateStatus[]>(`${base(hostId)}/status`),
    refetchInterval: (query) => (query.state.data?.some((s) => s.status === 'PENDING') ? POLL_MS : false),
  })
}

function useInvalidateRoutes(hostId: string) {
  const queryClient = useQueryClient()
  return () => queryClient.invalidateQueries({ queryKey: routesKey(hostId) })
}

export function usePublishRoute(hostId: string) {
  const invalidate = useInvalidateRoutes(hostId)
  // gcTime 0: the request can hold basic auth passwords and must not linger in the mutation cache.
  return useMutation({
    gcTime: 0,
    mutationFn: (request: RouteRequest) => apiFetch<ProxyRoute>(base(hostId), { method: 'POST', body: request }),
    onSuccess: invalidate,
  })
}

export function useUpdateRoute(hostId: string) {
  const invalidate = useInvalidateRoutes(hostId)
  // gcTime 0: the request can hold basic auth passwords and must not linger in the mutation cache.
  return useMutation({
    gcTime: 0,
    mutationFn: ({ id, ...request }: RouteRequest & { id: string }) => apiFetch<ProxyRoute>(`${base(hostId)}/${id}`, { method: 'PUT', body: request }),
    onSuccess: invalidate,
  })
}

export function useUnpublishRoute(hostId: string) {
  const invalidate = useInvalidateRoutes(hostId)
  return useMutation({
    mutationFn: (id: string) => apiFetch<void>(`${base(hostId)}/${id}`, { method: 'DELETE' }),
    onSuccess: invalidate,
  })
}
