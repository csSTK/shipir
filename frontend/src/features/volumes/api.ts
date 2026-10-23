import { useQuery } from '@tanstack/react-query'
import { apiFetch } from '../../shared/api/client.ts'
import { useDeleteResourceMutation } from '../hosts/deleteResource.ts'

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
