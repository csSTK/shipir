import { useQuery } from '@tanstack/react-query'
import { apiFetch } from '../../shared/api/client.ts'
import { useDeleteResourceMutation } from '../hosts/deleteResource.ts'
import { useCreateResourceMutation } from '../hosts/createResource.ts'
import i18n from 'i18next'

/** One volume of `GET /api/hosts/{id}/volumes`; `created` is usually null. */
export interface Volume {
  name: string
  driver: string
  mountpoint: string
  created: string | null
  inUse: boolean
}

export function useVolumes(hostId: string) {
  return useQuery({
    queryKey: ['hosts', hostId, 'volumes'],
    queryFn: () => apiFetch<Volume[]>(`/hosts/${hostId}/volumes`),
  })
}

/** Argument is the name. */
export function useDeleteVolume(hostId: string) {
  return useDeleteResourceMutation(hostId, 'volumes', (ref: string) => ({ ref }))
}

export interface VolumeSpec {
  name: string
  driver?: 'local'
}

/** Mirrors the server: letters, digits, `_ . -`, starting alphanumeric, up to 128 characters. */
export const RESOURCE_NAME_PATTERN = /^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}$/

export function validateVolumeName(name: string): string | null {
  if (!name.trim()) return i18n.t('volumes:api.enterAName')
  return RESOURCE_NAME_PATTERN.test(name) ? null : i18n.t('volumes:api.useLettersDigitsAndStart')
}

export function useCreateVolume(hostId: string) {
  return useCreateResourceMutation<VolumeSpec>(hostId, 'volumes')
}
