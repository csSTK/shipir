import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { apiFetch } from '../../shared/api/client.ts'

export type SearchResultType = 'HOST' | 'DEPLOYMENT' | 'CONTAINER'

export interface SearchResult {
  type: SearchResultType
  /** Host id, managed deployment id (null for an unmanaged compose project) or container id. */
  id: string | null
  name: string
  hostId: string
  hostName: string
  managed: boolean
  /** Deployment status or container image. */
  detail: string | null
}

export interface SearchResponse {
  results: SearchResult[]
  truncated: boolean
}

export const MIN_QUERY_LENGTH = 2
const DEBOUNCE_MS = 200

/** Where a result lives in the app. */
export function routeFor(r: SearchResult): string {
  if (r.type === 'HOST') return `/environments/${r.hostId}`
  if (r.type === 'CONTAINER') return `/environments/${r.hostId}/containers/${r.id}`
  if (r.managed && r.id) return `/environments/${r.hostId}/deployments/${r.id}`
  return `/environments/${r.hostId}/containers?deployment=${encodeURIComponent(r.name)}`
}

/** Debounced global search; only queries the server with at least two characters. */
export function useSearch(query: string) {
  const trimmed = query.trim()
  const [debounced, setDebounced] = useState(trimmed)
  useEffect(() => {
    const timer = setTimeout(() => setDebounced(trimmed), DEBOUNCE_MS)
    return () => clearTimeout(timer)
  }, [trimmed])
  const active = debounced.length >= MIN_QUERY_LENGTH && trimmed.length >= MIN_QUERY_LENGTH
  const result = useQuery({
    queryKey: ['search', debounced],
    queryFn: () => apiFetch<SearchResponse>(`/search?q=${encodeURIComponent(debounced)}`),
    enabled: active,
    placeholderData: keepPreviousData,
    staleTime: 10_000,
  })
  return {
    data: active ? result.data : undefined,
    // Typing (debounce pending) or request in flight.
    isSearching: trimmed.length >= MIN_QUERY_LENGTH && (debounced !== trimmed || result.isFetching),
    isError: active && result.isError,
  }
}
