import { useQuery } from '@tanstack/react-query'
import { apiFetch } from '../../shared/api/client.ts'
import type { Range } from '../../shared/charts/series.ts'

export const RANGES: { value: Range; label: string }[] = [
  { value: '1h', label: 'Last hour' },
  { value: '6h', label: 'Last 6 hours' },
  { value: '24h', label: 'Last 24 hours' },
  { value: '7d', label: 'Last 7 days' },
  { value: '30d', label: 'Last 30 days' },
]
export const DEFAULT_RANGE: Range = '24h'

export function parseRange(value: string | null): Range {
  return RANGES.find((r) => r.value === value)?.value ?? DEFAULT_RANGE
}

export interface HistoryPoint {
  time: string
  cpuPercent: number
  memoryBytes: number
  networkRxBytes: number
  networkTxBytes: number
  blockReadBytes: number
  blockWriteBytes: number
}

/** `GET /api/hosts/{h}/stats/history`; network and block values are cumulative counters (bucket maximum). */
export interface StatsHistory {
  range: Range
  from: string
  to: string
  bucketSeconds: number
  seriesTotal: number
  truncated: boolean
  series: { containerId: string; points: HistoryPoint[] }[]
}

const STALE_MS = 60_000

/** History of one container (`containerId` set) or of the 50 most recently active containers of the host. */
export function useStatsHistory(hostId: string, range: Range, containerId?: string, enabled = true) {
  return useQuery({
    queryKey: ['hosts', hostId, 'stats-history', containerId ?? 'all', range],
    queryFn: () => {
      const query = new URLSearchParams({ range })
      if (containerId) query.set('container', containerId)
      return apiFetch<StatsHistory>(`/hosts/${hostId}/stats/history?${query}`)
    },
    enabled,
    staleTime: STALE_MS,
    // Samples are recorded once a minute; never poll faster than that.
    refetchInterval: STALE_MS,
    placeholderData: (previous) => previous,
  })
}
