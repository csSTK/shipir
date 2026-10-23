import { useMutation, useQueryClient } from '@tanstack/react-query'
import { ApiError, apiFetch } from '../../shared/api/client.ts'
import { describeDeploymentError } from '../deployments/deploymentErrors.ts'
import { HOSTS_OVERVIEW_KEY } from './api.ts'
import { describeResourceError } from './format.ts'

/**
 * DELETE `/hosts/{hostId}/{collection}/{ref}`; refreshes every list of the host (resources and
 * deployments, whose rows derive from containers) and the overview counts.
 */
export function useDeleteResourceMutation<V>(hostId: string, collection: string, toRequest: (v: V) => { ref: string; force?: boolean }) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (variables: V) => {
      const { ref, force } = toRequest(variables)
      const query = force === undefined ? '' : `?force=${force}`
      return apiFetch<void>(`/hosts/${hostId}/${collection}/${encodeURIComponent(ref)}${query}`, { method: 'DELETE' })
    },
    onSuccess: () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: ['hosts', hostId] }),
        queryClient.invalidateQueries({ queryKey: HOSTS_OVERVIEW_KEY }),
      ]),
  })
}

/** `ApiError.message` is the raw response body; the human text is its `message` field. */
function serverMessage(error: ApiError): string {
  try {
    const parsed: unknown = JSON.parse(error.message)
    if (parsed && typeof parsed === 'object' && 'message' in parsed && typeof parsed.message === 'string' && parsed.message) return parsed.message
  } catch {
    // not JSON: use the text as is
  }
  return error.message
}

/** Maps a failed delete to a message; `RESOURCE_IN_USE` shows the server's own explanation. */
export function describeDeleteError(error: unknown): string {
  if (error instanceof ApiError) {
    switch (error.code) {
      case 'RESOURCE_IN_USE':
        return serverMessage(error)
      case 'RESOURCE_NOT_FOUND':
        return 'This resource no longer exists. The list was refreshed.'
      case 'INVALID_RESOURCE_ID':
        return 'This resource cannot be deleted because its identifier is invalid.'
      case 'MANAGED_DEPLOYMENT':
        return describeDeploymentError(error, 'This resource belongs to a managed deployment.')
      default:
        if (error.status === 403) return 'You are not allowed to do this.'
        if (error.status === 502 || error.code === 'HOST_NOT_FOUND') return describeResourceError(error)
    }
  }
  return 'The resource could not be deleted. Try again.'
}
