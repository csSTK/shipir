import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ApiError, apiFetch } from '../../shared/api/client.ts'
import { HOSTS_OVERVIEW_KEY } from './api.ts'
import { describeResourceError } from './format.ts'
import i18n from 'i18next'

export type ImagePruneMode = 'DANGLING' | 'UNUSED'

export interface PruneItem {
  id: string
  name: string | null
  sizeBytes: number | null
  composeProject: string | null
}

export interface PruneGroup {
  count: number
  sizeBytes: number
  items: PruneItem[]
}

export interface PrunePreview {
  containers: PruneGroup
  images: PruneGroup
  volumes: PruneGroup
  networks: PruneGroup
  managedVolumesExcluded: number
}

export interface PruneRequest {
  containers: boolean
  images: ImagePruneMode | null
  volumes: boolean
  networks: boolean
  includeManagedVolumes: boolean
}

interface PruneCounts {
  removed: number
  skipped: number
  reclaimedBytes: number
}

export interface PruneResult {
  containers: PruneCounts
  images: PruneCounts
  volumes: PruneCounts
  networks: PruneCounts
  reclaimedBytes: number
}

/** Preview of what a prune would remove; refetched when the options change. */
export function usePrunePreview(hostId: string, images: ImagePruneMode, includeManagedVolumes: boolean) {
  return useQuery({
    queryKey: ['hosts', hostId, 'prune-preview', images, includeManagedVolumes],
    queryFn: () =>
      apiFetch<PrunePreview>(`/hosts/${hostId}/prune/preview?images=${images}&includeManagedVolumes=${includeManagedVolumes}`),
    staleTime: 0,
    gcTime: 0,
    retry: false,
  })
}

/** Runs the prune and refreshes all lists of the host and the overview counts. */
export function usePrune(hostId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (request: PruneRequest) => apiFetch<PruneResult>(`/hosts/${hostId}/prune`, { method: 'POST', body: request }),
    onSuccess: () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: ['hosts', hostId] }),
        queryClient.invalidateQueries({ queryKey: HOSTS_OVERVIEW_KEY }),
      ]),
  })
}

export function describePruneError(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.code === 'NOTHING_SELECTED') return i18n.t('hosts:prune.selectAtLeastOneKind')
    if (error.status === 403) return i18n.t('hosts:prune.youAreNotAllowedTo')
    if (error.status === 502 || error.code === 'HOST_NOT_FOUND') return describeResourceError(error)
  }
  return i18n.t('hosts:prune.theCleanupFailedTryAgain')
}
