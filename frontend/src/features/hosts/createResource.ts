import { useMutation, useQueryClient } from '@tanstack/react-query'
import { ApiError, apiFetch } from '../../shared/api/client.ts'
import { HOSTS_OVERVIEW_KEY } from './api.ts'
import { describeResourceError } from './format.ts'

/** Response of `POST /api/hosts/{id}/{containers|networks|volumes}`. */
export interface CreatedResource {
  id: string
  name: string | null
}

/** POST to the collection and refresh all lists of the host plus the overview counts. */
export function useCreateResourceMutation<T>(hostId: string, collection: 'containers' | 'networks' | 'volumes') {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (spec: T) => apiFetch<CreatedResource>(`/hosts/${hostId}/${collection}`, { method: 'POST', body: spec }),
    onSuccess: () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: ['hosts', hostId] }),
        queryClient.invalidateQueries({ queryKey: HOSTS_OVERVIEW_KEY }),
      ]),
  })
}

/** Server error to inline text; `invalidCode` is the 400 code of the resource kind. Field details are appended. */
export function describeCreateError(error: unknown, kind: string, invalidCode: string): string {
  if (error instanceof ApiError) {
    if (error.code === invalidCode) {
      return [`The ${kind} settings are invalid.`, ...(error.details ?? [])].join(' ')
    }
    switch (error.code) {
      case 'NAME_TAKEN':
        return `A ${kind} with this name already exists.`
      case 'ADMIN_REQUIRED':
        return 'Only administrators may use these options.'
      case 'RESOURCE_NOT_FOUND':
        return 'The image or network was not found on this environment. Pull the image first.'
      case 'CONTAINER_START_FAILED':
        return 'The container could not be started and was removed again.'
    }
    if (error.status === 403) return 'You are not allowed to create this.'
    if (error.status === 502 || error.code === 'HOST_NOT_FOUND') return describeResourceError(error)
  }
  return `The ${kind} could not be created. Try again.`
}
