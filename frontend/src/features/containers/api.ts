import { useQuery } from '@tanstack/react-query'
import { apiFetch } from '../../shared/api/client.ts'
import { useDeleteResourceMutation } from '../hosts/deleteResource.ts'

/** One container of `GET /api/hosts/{id}/containers`; compose fields are null outside Compose. */
export interface Container {
  id: string
  name: string
  image: string
  state: string
  status: string
  /** healthy, unhealthy, starting or null without healthcheck. */
  health: string | null
  created: string
  ports: string[]
  composeProject: string | null
  composeService: string | null
}

export function useContainers(hostId: string) {
  return useQuery({
    queryKey: ['hosts', hostId, 'containers'],
    queryFn: () => apiFetch<Container[]>(`/hosts/${hostId}/containers`),
  })
}

/** `force` stops a running container before removing it. */
export function useDeleteContainer(hostId: string) {
  return useDeleteResourceMutation(hostId, 'containers', ({ id, force }: { id: string; force: boolean }) => ({ ref: id, force }))
}
