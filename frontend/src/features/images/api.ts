import { useQuery } from '@tanstack/react-query'
import { apiFetch } from '../../shared/api/client.ts'

/** One image of `GET /api/hosts/{id}/images`; `repoTags` is empty for dangling images. */
export interface Image {
  id: string
  repoTags: string[]
  size: number | null
  created: string
  inUse: boolean
}

export function useImages(hostId: string) {
  return useQuery({
    queryKey: ['hosts', hostId, 'images'],
    queryFn: () => apiFetch<Image[]>(`/hosts/${hostId}/images`),
  })
}
