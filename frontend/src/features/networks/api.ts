import { useQuery } from '@tanstack/react-query'
import { apiFetch } from '../../shared/api/client.ts'
import { useDeleteResourceMutation } from '../hosts/deleteResource.ts'
import { useCreateResourceMutation } from '../hosts/createResource.ts'
import i18n from 'i18next'

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

export type NetworkDriver = 'bridge' | 'overlay' | 'macvlan' | 'ipvlan'

export interface NetworkSpec {
  name: string
  driver?: NetworkDriver
  internal?: boolean
  subnet?: string
}

export function useCreateNetwork(hostId: string) {
  return useCreateResourceMutation<NetworkSpec>(hostId, 'networks')
}

/** IPv4 `a.b.c.d/n` or IPv6 CIDR; the server has the final say. */
export function validateSubnet(subnet: string): string | null {
  const s = subnet.trim()
  if (!s) return null
  const v4 = /^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})\/(\d{1,2})$/.exec(s)
  if (v4) {
    const ok = v4.slice(1, 5).every((p) => Number(p) <= 255) && Number(v4[5]) <= 32
    return ok ? null : i18n.t('networks:api.enterAValidSubnetSuch')
  }
  const v6 = /^[0-9a-fA-F:]+\/(\d{1,3})$/.exec(s)
  if (v6 && s.includes(':') && Number(v6[1]) <= 128) return null
  return i18n.t('networks:api.enterAValidSubnetSuch')
}
