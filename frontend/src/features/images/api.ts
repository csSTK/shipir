import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ApiError, apiFetch } from '../../shared/api/client.ts'
import { HOSTS_OVERVIEW_KEY } from '../hosts/api.ts'
import { useDeleteResourceMutation } from '../hosts/deleteResource.ts'

/** One image of `GET /api/hosts/{id}/images`; `repoTags` is empty for dangling images. */
export interface Image {
  id: string
  repoTags: string[]
  size: number | null
  created: string
  inUse: boolean
  repoDigests?: string[]
  /** A newer digest exists in the registry for one of the tags. */
  updateAvailable?: boolean
}

export function useImages(hostId: string) {
  return useQuery({
    queryKey: ['hosts', hostId, 'images'],
    queryFn: () => apiFetch<Image[]>(`/hosts/${hostId}/images`),
  })
}

/** Argument is the id. */
export function useDeleteImage(hostId: string) {
  return useDeleteResourceMutation(hostId, 'images', (ref: string) => ({ ref }))
}

/** `POST /hosts/{id}/images/pull` answers 202; progress streams on `/topic/deployments/{runId}`. */
export function usePullImage(hostId: string) {
  return useMutation({
    mutationFn: (reference: string) =>
      apiFetch<{ runId: string }>(`/hosts/${hostId}/images/pull`, { method: 'POST', body: { reference } }),
  })
}

/** Refreshes everything a finished pull or update can change. */
export function useRefreshAfterPull(hostId: string) {
  const queryClient = useQueryClient()
  return () =>
    Promise.all([
      queryClient.invalidateQueries({ queryKey: ['hosts', hostId] }),
      queryClient.invalidateQueries({ queryKey: HOSTS_OVERVIEW_KEY }),
    ])
}

export function describePullError(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.code === 'INVALID_IMAGE_REFERENCE') return 'This is not a valid image reference. Example: nginx:1.27 or ghcr.io/org/app:latest.'
    if (error.code === 'HOST_NOT_FOUND') return 'This environment no longer exists.'
    if (error.status === 403) return 'You are not allowed to pull images here.'
  }
  return 'Could not start the pull. Try again.'
}

/** Normalises a container's image string to a tag reference (`nginx` -> `nginx:latest`). */
export function withTag(image: string): string {
  const lastSlash = image.lastIndexOf('/')
  return image.indexOf(':', lastSlash + 1) === -1 && !image.includes('@') ? `${image}:latest` : image
}
