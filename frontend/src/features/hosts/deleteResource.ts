import { useMutation, useQueryClient } from '@tanstack/react-query'
import { ApiError, apiFetch } from '../../shared/api/client.ts'
import { describeDeploymentError } from '../deployments/deploymentErrors.ts'
import { HOSTS_OVERVIEW_KEY } from './api.ts'
import { describeResourceError } from './format.ts'
import i18n from 'i18next'

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

/** Maps a failed delete to a translated message. */
export function describeDeleteError(error: unknown): string {
  if (error instanceof ApiError) {
    switch (error.code) {
      case 'RESOURCE_IN_USE':
        return i18n.t('hosts:deleteResource.resourceInUse')
      case 'RESOURCE_NOT_FOUND':
        return i18n.t('hosts:deleteResource.thisResourceNoLongerExists')
      case 'INVALID_RESOURCE_ID':
        return i18n.t('hosts:deleteResource.thisResourceCannotBeDeleted')
      case 'MANAGED_BY_COMPOSE':
        return i18n.t('hosts:deleteResource.managedByCompose')
      case 'PROXY_IMAGE_UNRESOLVED':
        return i18n.t('hosts:deleteResource.proxyImageUnresolved')
      case 'MANAGED_DEPLOYMENT':
        return describeDeploymentError(error, i18n.t('hosts:deleteResource.thisResourceBelongsToA'))
      default:
        if (error.status === 403) return i18n.t('hosts:deleteResource.youAreNotAllowedTo')
        if (error.status === 502 || error.code === 'HOST_NOT_FOUND') return describeResourceError(error)
    }
  }
  return i18n.t('hosts:deleteResource.theResourceCouldNotBe')
}
