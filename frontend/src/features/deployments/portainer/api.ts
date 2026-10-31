import { useMutation } from '@tanstack/react-query'
import { apiFetch } from '../../../shared/api/client.ts'
import type { EnvEntry } from '../api.ts'

/** `POST /api/portainer-import/stacks` item. */
export interface PortainerStack {
  id: number
  name: string
  type: string
  endpointId: number
  supported: boolean
  reason?: string | null
}

/** `POST /api/portainer-import/stacks/{id}`: the stack's Compose file and variables. */
export interface PortainerStackFile {
  name: string
  yaml: string
  env: EnvEntry[]
}

export interface PortainerConnection {
  url: string
  apiToken: string
}

const BASE = '/portainer-import/stacks'

/*
 * Both calls are mutations on purpose: the API token is in the request body, and a query would put it
 * into the query key and cache. Mutations keep variables only in the (unpersisted) mutation state.
 */
export function useListPortainerStacks() {
  return useMutation({
    mutationFn: (connection: PortainerConnection) => apiFetch<PortainerStack[]>(BASE, { method: 'POST', body: connection }),
  })
}

export function useFetchPortainerStack() {
  return useMutation({
    mutationFn: ({ id, ...connection }: PortainerConnection & { id: number }) =>
      apiFetch<PortainerStackFile>(`${BASE}/${id}`, { method: 'POST', body: connection }),
  })
}
