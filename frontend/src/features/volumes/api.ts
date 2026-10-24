import { useQuery } from '@tanstack/react-query'
import { apiFetch } from '../../shared/api/client.ts'
import { useDeleteResourceMutation } from '../hosts/deleteResource.ts'
import { useCreateResourceMutation } from '../hosts/createResource.ts'

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
  if (!name.trim()) return 'Enter a name.'
  return RESOURCE_NAME_PATTERN.test(name) ? null : 'Use letters, digits, _ . - and start with a letter or digit (max 128).'
}

export function useCreateVolume(hostId: string) {
  return useCreateResourceMutation<VolumeSpec>(hostId, 'volumes')
}
