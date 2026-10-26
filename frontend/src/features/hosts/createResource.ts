import { useMutation, useQueryClient } from '@tanstack/react-query'
import { ApiError, apiFetch } from '../../shared/api/client.ts'
import { HOSTS_OVERVIEW_KEY } from './api.ts'
import { describeResourceError } from './format.ts'
import i18n from 'i18next'

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
export type ResourceKind = 'container' | 'image' | 'volume' | 'network'

/** Translated singular noun of a resource kind ("container"). */
export function resourceNoun(kind: ResourceKind): string {
  return i18n.t(`hosts:kinds.${kind}`)
}

/** `operation` only changes the generic fallback text ("could not be created" / "recreated" / "duplicated"). */
export function describeCreateError(
  error: unknown,
  resourceKind: ResourceKind,
  invalidCode: string,
  operation: 'create' | 'recreate' | 'duplicate' = 'create',
): string {
  const kind = resourceNoun(resourceKind)
  if (error instanceof ApiError) {
    if (error.code === invalidCode) {
      return [i18n.t('hosts:createResource.theKindSettingsAreInvalid', { kind }), ...(error.details ?? [])].join(' ')
    }
    switch (error.code) {
      case 'NAME_TAKEN':
        return i18n.t('hosts:createResource.aKindWithThisName', { kind })
      case 'ADMIN_REQUIRED':
        return i18n.t('hosts:createResource.onlyAdministratorsMayUseThese')
      case 'RESOURCE_NOT_FOUND':
        return i18n.t('hosts:createResource.theImageOrNetworkWas')
      case 'CONTAINER_START_FAILED':
        return i18n.t('hosts:createResource.theContainerCouldNotBe')
    }
    if (error.status === 403) return i18n.t('hosts:createResource.youAreNotAllowedTo')
    if (error.status === 502 || error.code === 'HOST_NOT_FOUND') return describeResourceError(error)
  }
  return i18n.t('hosts:createResource.theKindCouldNotBe', { kind, context: operation })
}
