import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiFetch } from '../../../shared/api/client.ts'
import type { ProxyType } from '../api.ts'

export interface ManagedFile {
  name: string
  content: string
}

/** `GET /hosts/{id}/proxy/config`: generated files (read-only, secrets redacted by the server) and the custom file. */
export interface ProxyConfig {
  type: ProxyType
  managed: ManagedFile[]
  customFileName: string
  custom: string
  customUpdatedAt: string | null
}

export interface CustomConfigVersion {
  id: string
  createdAt: string
  createdBy: string
  content: string
}

const configKey = (hostId: string) => ['hosts', hostId, 'proxy', 'config'] as const
const base = (hostId: string) => `/hosts/${hostId}/proxy/config`

// gcTime 0 on everything below: the config can contain hostnames and credentials-adjacent settings, so it must not
// linger in the query cache once the editor is unmounted.
export function useProxyConfig(hostId: string, enabled: boolean) {
  return useQuery({
    queryKey: [...configKey(hostId), 'current'],
    queryFn: () => apiFetch<ProxyConfig>(base(hostId)),
    enabled,
    gcTime: 0,
  })
}

export function useCustomConfigVersions(hostId: string, enabled: boolean) {
  return useQuery({
    queryKey: [...configKey(hostId), 'versions'],
    queryFn: () => apiFetch<CustomConfigVersion[]>(`${base(hostId)}/custom/versions`),
    enabled,
    gcTime: 0,
  })
}

/** Saves the custom file only; the generated files are never sent. */
export function useSaveCustomConfig(hostId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    gcTime: 0,
    mutationFn: (content: string) => apiFetch<ProxyConfig>(`${base(hostId)}/custom`, { method: 'PUT', body: { content } }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: configKey(hostId) }),
  })
}

/** Restores an earlier custom file; the server validates it again like a normal save. */
export function useRestoreCustomConfig(hostId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    gcTime: 0,
    mutationFn: (versionId: string) => apiFetch<ProxyConfig>(`${base(hostId)}/custom/restore/${versionId}`, { method: 'POST' }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: configKey(hostId) }),
  })
}
