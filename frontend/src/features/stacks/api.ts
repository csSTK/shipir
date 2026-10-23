import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiFetch } from '../../shared/api/client.ts'

export type StackStatus = 'RUNNING' | 'PARTIAL' | 'STOPPED'

/** One Compose project of `GET /api/hosts/{id}/stacks`. */
export interface StackSummary {
  name: string
  containerCount: number
  runningCount: number
  status: StackStatus
  workingDir: string | null
  /** True when Shipir manages this project; optional for older servers. */
  managed?: boolean
  managedStackId?: string | null
}

export function useStacks(hostId: string) {
  return useQuery({
    queryKey: ['hosts', hostId, 'stacks'],
    queryFn: () => apiFetch<StackSummary[]>(`/hosts/${hostId}/stacks`),
  })
}

export type ManagedStackStatus = 'STOPPED' | 'RUNNING' | 'ERROR'
export type StackSource = 'FILE' | 'GIT'
export type VersionSource = 'MANUAL' | 'GIT' | 'ROLLBACK'

/** `GET /api/hosts/{id}/managed-stacks` item. */
export interface ManagedStack {
  id: string
  name: string
  status: ManagedStackStatus
  source: StackSource
  lastDeployedAt: string | null
  createdAt: string
}

export interface ManagedStackDetail extends ManagedStack {
  yaml: string
  /** Variable names only; values are never part of this response. */
  envKeys: string[]
}

export interface EnvEntry {
  key: string
  value: string
}

export interface StackVersion {
  id: string
  source: VersionSource
  commitSha: string | null
  createdBy: string | null
  createdAt: string
  current: boolean
  /** False marks the editable draft. */
  deployed: boolean
}

/** Deploy and rollback answer 202; output streams on `/topic/deployments/{deploymentId}`. */
export interface Deployment {
  deploymentId: string
  versionId: string
}

export interface CreateStackInput {
  name: string
  yaml: string
  env: EnvEntry[]
}

const managedBase = (hostId: string) => `/hosts/${hostId}/managed-stacks`
const managedKey = (hostId: string) => ['hosts', hostId, 'managed-stacks'] as const

export function useManagedStacks(hostId: string) {
  return useQuery({ queryKey: managedKey(hostId), queryFn: () => apiFetch<ManagedStack[]>(managedBase(hostId)) })
}

export function useManagedStack(hostId: string, stackId: string) {
  return useQuery({
    queryKey: [...managedKey(hostId), stackId],
    queryFn: () => apiFetch<ManagedStackDetail>(`${managedBase(hostId)}/${stackId}`),
  })
}

export function useStackVersions(hostId: string, stackId: string) {
  return useQuery({
    queryKey: [...managedKey(hostId), stackId, 'versions'],
    queryFn: () => apiFetch<StackVersion[]>(`${managedBase(hostId)}/${stackId}/versions`),
  })
}

/** Refreshes everything that shows this host's stacks (managed list, details, versions, live list). */
function useInvalidateStacks(hostId: string) {
  const queryClient = useQueryClient()
  return () =>
    Promise.all([
      queryClient.invalidateQueries({ queryKey: managedKey(hostId) }),
      queryClient.invalidateQueries({ queryKey: ['hosts', hostId, 'stacks'] }),
    ])
}

export function useCreateStack(hostId: string) {
  const invalidate = useInvalidateStacks(hostId)
  return useMutation({
    mutationFn: (input: CreateStackInput) =>
      apiFetch<ManagedStackDetail>(managedBase(hostId), { method: 'POST', body: input }),
    onSuccess: () => void invalidate(),
  })
}

export function useDeleteStack(hostId: string) {
  const invalidate = useInvalidateStacks(hostId)
  return useMutation({
    mutationFn: (stackId: string) => apiFetch<void>(`${managedBase(hostId)}/${stackId}`, { method: 'DELETE' }),
    onSuccess: () => void invalidate(),
  })
}

export function useDeployStack(hostId: string) {
  const invalidate = useInvalidateStacks(hostId)
  return useMutation({
    mutationFn: (stackId: string) =>
      apiFetch<Deployment>(`${managedBase(hostId)}/${stackId}/deploy`, { method: 'POST' }),
    onSuccess: () => void invalidate(),
  })
}

export function useStopStack(hostId: string) {
  const invalidate = useInvalidateStacks(hostId)
  return useMutation({
    mutationFn: (stackId: string) => apiFetch<ManagedStack>(`${managedBase(hostId)}/${stackId}/stop`, { method: 'POST' }),
    onSuccess: () => void invalidate(),
  })
}

export function useRollbackStack(hostId: string) {
  const invalidate = useInvalidateStacks(hostId)
  return useMutation({
    mutationFn: ({ stackId, versionId }: { stackId: string; versionId: string }) =>
      apiFetch<Deployment>(`${managedBase(hostId)}/${stackId}/versions/${versionId}/rollback`, { method: 'POST' }),
    onSuccess: () => void invalidate(),
  })
}
