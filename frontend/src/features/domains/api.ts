import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiFetch } from '../../shared/api/client.ts'

export type ProxyType = 'TRAEFIK' | 'CADDY'
export type CertMode = 'ACME_HTTP' | 'ACME_DNS' | 'INTERNAL' | 'CUSTOM'
export type SetupBlockedReason = 'EXISTING_PROXY' | 'PORTS_IN_USE' | 'UNKNOWN'

/** The managed proxy of a host. `deploymentStatus` is null when its deployment no longer exists. */
export interface ProxyInstance {
  type: ProxyType
  acmeEmail: string | null
  defaultCertMode: CertMode
  dnsProvider: string | null
  dnsCredentialsHint: string | null
  deploymentId: string
  deploymentStatus: 'STOPPED' | 'RUNNING' | 'ERROR' | null
}

/** `GET /api/hosts/{id}/proxy/managed`: the instance, or null together with why a setup is not possible. */
export interface ProxyManaged {
  instance: ProxyInstance | null
  setupBlockedReason: SetupBlockedReason | null
}

export interface ProxySetupRequest {
  type: ProxyType
  acmeEmail?: string
  defaultCertMode: CertMode
}

export interface ProxySetupResult {
  instance: ProxyInstance
  /** Deployment run streamed on `/topic/deployments/{runId}`; null when nothing was redeployed. */
  runId: string | null
}

const proxyKey = (hostId: string) => ['hosts', hostId, 'proxy', 'managed'] as const

export interface ManagedProxyOptions {
  enabled?: boolean
  /** Per-observer overrides; unset means the app-wide query defaults. */
  retry?: boolean
  refetchOnWindowFocus?: boolean
}

/**
 * The managed proxy of a host. The Domains page, the publish dialogs and the deployment wizard share this cache entry;
 * the wizard passes `retry: false` and `refetchOnWindowFocus: false` as it always has.
 */
export function useManagedProxy(hostId: string, options: ManagedProxyOptions = {}) {
  return useQuery({
    queryKey: proxyKey(hostId),
    queryFn: () => apiFetch<ProxyManaged>(`/hosts/${hostId}/proxy/managed`),
    enabled: options.enabled ?? true,
    ...(options.retry !== undefined && { retry: options.retry }),
    ...(options.refetchOnWindowFocus !== undefined && { refetchOnWindowFocus: options.refetchOnWindowFocus }),
  })
}

export function useSetupProxy(hostId: string) {
  return useMutation({
    mutationFn: (request: ProxySetupRequest) =>
      apiFetch<ProxySetupResult>(`/hosts/${hostId}/proxy/managed`, { method: 'POST', body: request }),
  })
}

/** Re-reads the proxy state, e.g. after the setup run finished. */
export function useRefreshProxy(hostId: string) {
  const queryClient = useQueryClient()
  return () => queryClient.invalidateQueries({ queryKey: proxyKey(hostId) })
}

export interface ProxyUpdateRequest {
  acmeEmail?: string
  defaultCertMode?: CertMode
  dnsProvider?: string
  /** Variable name -> secret value. Non-empty replaces all stored credentials; omitted keeps them. */
  dnsCredentials?: Record<string, string>
  removeDnsCredentials?: boolean
}

/** `PUT /hosts/{id}/proxy/managed`: changes the proxy settings; `runId` is set when the proxy was redeployed. */
export function useUpdateProxy(hostId: string) {
  const queryClient = useQueryClient()
  // gcTime 0: the request can hold DNS credentials and must not linger in the mutation cache.
  return useMutation({
    gcTime: 0,
    mutationFn: (request: ProxyUpdateRequest) => apiFetch<ProxySetupResult>(`/hosts/${hostId}/proxy/managed`, { method: 'PUT', body: request }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: proxyKey(hostId) }),
  })
}
