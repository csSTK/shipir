import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiFetch } from '../../../shared/api/client.ts'

/** An uploaded certificate. The private key is never returned by the server. */
export interface ProxyCertificate {
  id: string
  name: string
  domains: string[]
  /** ISO timestamp of the leaf certificate's expiry. */
  notAfter: string
  /** Hostnames of the routes using this certificate. */
  usedByRoutes: string[]
}

export interface UploadCertificateRequest {
  name: string
  /** Certificate chain as PEM, leaf first. */
  certPem: string
  keyPem: string
}

export interface RenewCertificateRequest {
  certPem: string
  keyPem: string
}

const certsKey = (hostId: string) => ['hosts', hostId, 'proxy', 'certificates'] as const
const base = (hostId: string) => `/hosts/${hostId}/proxy/certificates`

export function useCertificates(hostId: string) {
  return useQuery({
    queryKey: certsKey(hostId),
    queryFn: () => apiFetch<ProxyCertificate[]>(base(hostId)),
  })
}

export function useUploadCertificate(hostId: string) {
  const queryClient = useQueryClient()
  // gcTime 0: the request holds the private key and must not linger in the mutation cache.
  return useMutation({
    gcTime: 0,
    mutationFn: (request: UploadCertificateRequest) => apiFetch<ProxyCertificate>(base(hostId), { method: 'POST', body: request }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: certsKey(hostId) }),
  })
}

export function useRenewCertificate(hostId: string) {
  const queryClient = useQueryClient()
  // gcTime 0: the request holds the private key and must not linger in the mutation cache.
  return useMutation({
    gcTime: 0,
    mutationFn: ({ id, ...request }: RenewCertificateRequest & { id: string }) =>
      apiFetch<ProxyCertificate>(`${base(hostId)}/${id}`, { method: 'PUT', body: request }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: certsKey(hostId) }),
  })
}

export function useDeleteCertificate(hostId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: string) => apiFetch<void>(`${base(hostId)}/${id}`, { method: 'DELETE' }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: certsKey(hostId) }),
  })
}
