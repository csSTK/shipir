import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiFetch, ApiError } from '../../../shared/api/client.ts'

export interface ConfigFileEntry {
  path: string
  directory: boolean
  size: number
  modifiedAt: string
  readOnly: boolean
}
export interface ConfigFile {
  path: string
  content: string
  size: number
  modifiedAt: string
  readOnly: boolean
}
export interface ConfigFileBackup {
  id: string
  createdAt: string
  size: number
}

const filesBase = (hostId: string, deploymentId: string) => `/hosts/${hostId}/managed-deployments/${deploymentId}/files`
const withPath = (hostId: string, deploymentId: string, path: string, suffix = '') =>
  `${filesBase(hostId, deploymentId)}${suffix}?path=${encodeURIComponent(path)}`
const filesKey = (hostId: string, deploymentId: string) => ['hosts', hostId, 'managed-deployments', deploymentId, 'files'] as const

export function useConfigTree(hostId: string, deploymentId: string) {
  return useQuery({
    queryKey: [...filesKey(hostId, deploymentId), 'tree'],
    queryFn: () => apiFetch<ConfigFileEntry[]>(filesBase(hostId, deploymentId)),
  })
}

export function useConfigFile(hostId: string, deploymentId: string, path: string | null) {
  return useQuery({
    queryKey: [...filesKey(hostId, deploymentId), 'file', path],
    queryFn: () => apiFetch<ConfigFile>(withPath(hostId, deploymentId, path ?? '')),
    enabled: path !== null,
  })
}

export function useConfigBackups(hostId: string, deploymentId: string, path: string | null) {
  return useQuery({
    queryKey: [...filesKey(hostId, deploymentId), 'backups', path],
    queryFn: () => apiFetch<ConfigFileBackup[]>(withPath(hostId, deploymentId, path ?? '', '/backups')),
    enabled: path !== null,
  })
}

function useInvalidateFiles(hostId: string, deploymentId: string) {
  const queryClient = useQueryClient()
  return () => queryClient.invalidateQueries({ queryKey: filesKey(hostId, deploymentId) })
}

/** Writes (creates or overwrites) a file; the server backs up the previous content. */
export function useSaveConfigFile(hostId: string, deploymentId: string) {
  const invalidate = useInvalidateFiles(hostId, deploymentId)
  return useMutation({
    mutationFn: ({ path, content }: { path: string; content: string }) =>
      apiFetch<ConfigFile>(withPath(hostId, deploymentId, path), { method: 'PUT', body: { content } }),
    onSuccess: () => void invalidate(),
  })
}

export function useDeleteConfigFile(hostId: string, deploymentId: string) {
  const invalidate = useInvalidateFiles(hostId, deploymentId)
  return useMutation({
    mutationFn: (path: string) => apiFetch<void>(withPath(hostId, deploymentId, path), { method: 'DELETE' }),
    onSuccess: () => void invalidate(),
  })
}

export function useRestoreConfigFile(hostId: string, deploymentId: string) {
  const invalidate = useInvalidateFiles(hostId, deploymentId)
  return useMutation({
    mutationFn: ({ path, backupId }: { path: string; backupId: string }) =>
      apiFetch<ConfigFile>(withPath(hostId, deploymentId, path, '/restore'), { method: 'POST', body: { backupId } }),
    onSuccess: () => void invalidate(),
  })
}

/** Maps config file API failures to short user-facing messages. */
export function describeConfigError(error: unknown, fallback: string): string {
  if (error instanceof ApiError) {
    switch (error.code) {
      case 'INVALID_PATH':
        return 'This path is not valid. Use a relative path inside the deployment folder.'
      case 'EXCLUDED_PATH':
        return 'This file is managed by Shipir (compose.yaml, .env) and cannot be edited here.'
      case 'NOT_TEXT':
        return 'This file is not a text file and cannot be edited here.'
      case 'NOT_A_FILE':
        return 'This path is not a file.'
      case 'INVALID_BACKUP_ID':
      case 'BACKUP_NOT_FOUND':
        return 'This backup no longer exists.'
      case 'READ_ONLY_PATH':
        return 'Files from the Git checkout are read-only.'
      case 'FILE_NOT_FOUND':
        return 'This file no longer exists.'
      case 'DEPLOYMENT_NOT_FOUND':
        return 'This deployment no longer exists.'
      case 'FILE_TOO_LARGE':
        return 'This file is too large to edit here.'
    }
    if (error.status === 403) return 'You are not allowed to do this.'
  }
  return fallback
}

/** Monaco language id by file extension; YAML stays plain text so the Compose schema is not applied to config files. */
export function languageFor(path: string): string {
  const name = path.split('/').pop()?.toLowerCase() ?? ''
  const ext = name.includes('.') ? name.slice(name.lastIndexOf('.') + 1) : ''
  switch (ext) {
    case 'json':
      return 'json'
    case 'xml':
      return 'xml'
    case 'ini':
    case 'conf':
    case 'cfg':
    case 'properties':
    case 'toml':
      return 'ini'
    case 'sh':
      return 'shell'
    case 'md':
      return 'markdown'
    case 'js':
      return 'javascript'
    case 'sql':
      return 'sql'
    case 'html':
      return 'html'
    case 'css':
      return 'css'
    default:
      return 'plaintext'
  }
}
