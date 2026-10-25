import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiFetch } from '../../../shared/api/client.ts'

/** One token as listed by `GET /api/account/tokens`: metadata and the non-secret prefix, never the token. */
export interface ApiToken {
  id: string
  name: string
  prefix: string
  createdAt: string
  expiresAt: string
  lastUsedAt: string | null
}

/** `POST /api/account/tokens`: `token` is the plaintext, returned exactly once. */
export interface CreatedApiToken extends Omit<ApiToken, 'lastUsedAt'> {
  token: string
}

const TOKENS_KEY = ['account', 'tokens'] as const

export function useApiTokens() {
  return useQuery({ queryKey: TOKENS_KEY, queryFn: () => apiFetch<ApiToken[]>('/account/tokens') })
}

export function useCreateApiToken() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (input: { name: string; expiresAt?: string }) =>
      apiFetch<CreatedApiToken>('/account/tokens', { method: 'POST', body: input }),
    // The plaintext token must not linger in the mutation cache.
    gcTime: 0,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: TOKENS_KEY }),
  })
}

export function useRevokeApiToken() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: string) => apiFetch<void>(`/account/tokens/${id}`, { method: 'DELETE' }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: TOKENS_KEY }),
  })
}
