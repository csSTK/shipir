import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ApiError, apiFetch } from '../../shared/api/client.ts'
import { HOSTS_OVERVIEW_KEY } from '../hosts/api.ts'
import { describeResourceError } from '../hosts/format.ts'
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

export type ContainerAction = 'start' | 'stop' | 'restart' | 'pause' | 'unpause' | 'kill'

/** Actions offered for a container state: stopped can start, running can stop/restart/pause/kill, paused can unpause. */
export function actionsFor(state: string): ContainerAction[] {
  if (state === 'running') return ['stop', 'restart', 'pause', 'kill']
  if (state === 'paused') return ['unpause']
  if (state === 'exited' || state === 'created') return ['start']
  return []
}

/** POST `/hosts/{id}/containers/{containerId}/{action}`; refreshes containers, deployments and overview counts (also on failure, the state may have changed). */
export function useContainerAction(hostId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, action }: { id: string; action: ContainerAction }) =>
      apiFetch<void>(`/hosts/${hostId}/containers/${encodeURIComponent(id)}/${action}`, { method: 'POST' }),
    onSettled: () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: ['hosts', hostId] }),
        queryClient.invalidateQueries({ queryKey: HOSTS_OVERVIEW_KEY }),
      ]),
  })
}

export function describeActionError(error: unknown): string {
  if (error instanceof ApiError) {
    switch (error.code) {
      case 'INVALID_CONTAINER_STATE':
        return 'The container is not in a state that allows this action. The list was refreshed.'
      case 'RESOURCE_NOT_FOUND':
        return 'This container no longer exists. The list was refreshed.'
      case 'INVALID_RESOURCE_ID':
        return 'The container identifier is invalid.'
    }
    if (error.status === 403) return 'You are not allowed to do this.'
    if (error.status === 502 || error.code === 'HOST_NOT_FOUND') return describeResourceError(error)
  }
  return 'The action failed. Try again.'
}
