import { useQuery } from '@tanstack/react-query'
import { apiFetch } from '../../shared/api/client.ts'

/** One category of `GET /api/hosts/{id}/disk-usage`; null where Docker did not report the value. */
export interface DiskCategory {
  count: number | null
  totalSize: number | null
  reclaimableSize: number | null
}

export type LargestCategory = 'IMAGE' | 'CONTAINER' | 'VOLUME' | 'BUILD_CACHE'

/** One of the biggest objects (max 10, biggest first); `name` is a display string from the server. */
export interface LargestItem {
  category: LargestCategory
  name: string
  size: number
}

export interface DiskUsage {
  images: DiskCategory
  containers: DiskCategory
  volumes: DiskCategory
  buildCache: DiskCategory
  largest: LargestItem[]
}

/** The endpoint takes about 3 s, so the data is kept for a long time and refreshed on demand only. */
const DISK_USAGE_STALE_MS = 10 * 60_000

/**
 * Disk usage of one environment, fetched once per visit (no polling, no refetch on focus).
 * Shares the `['hosts', id]` prefix, so host-level invalidations (e.g. after a prune) refresh it too.
 */
export function useDiskUsage(hostId: string) {
  return useQuery({
    queryKey: ['hosts', hostId, 'disk-usage'],
    queryFn: () => apiFetch<DiskUsage>(`/hosts/${hostId}/disk-usage`),
    staleTime: DISK_USAGE_STALE_MS,
    refetchOnWindowFocus: false,
    retry: false,
  })
}
