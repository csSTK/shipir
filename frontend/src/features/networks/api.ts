import { useQuery } from '@tanstack/react-query'
import { apiFetch } from '../../shared/api/client.ts'
import { useDeleteResourceMutation } from '../hosts/deleteResource.ts'

/** One network of `GET /api/hosts/{id}/networks`; `subnet` is the first IPAM subnet or null. */
export interface Network {
  id: string
  name: string
  driver: string
  scope: string
  subnet: string | null
  internal: boolean
  containerCount: number
}

export function useNetworks(hostId: string) {
  return useQuery({
    queryKey: ['hosts', hostId, 'networks'],
    queryFn: () => apiFetch<Network[]>(`/hosts/${hostId}/networks`),
  })
}

/** Argument is the id. */
export function useDeleteNetwork(hostId: string) {
  return useDeleteResourceMutation(hostId, 'networks', (ref: string) => ({ ref }))
}
