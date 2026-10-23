import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ApiError, apiFetch } from '../../shared/api/client.ts'

export type SetupStep = 'PASSWORD' | 'ENVIRONMENT'

export interface CurrentUser {
  username: string
  role: 'ADMIN' | 'OPERATOR' | 'VIEWER'
  setupRequired: boolean
  /** Which first-start step is pending; `null` once setup is complete. */
  setupStep?: SetupStep | null
}

export interface LoginRequest {
  username: string
  password: string
}

export const CURRENT_USER_KEY = ['auth', 'me'] as const

/** Resolves to the session user, or `null` when not signed in (401). */
export function useCurrentUser() {
  return useQuery({
    queryKey: CURRENT_USER_KEY,
    queryFn: async () => {
      try {
        return await apiFetch<CurrentUser>('/auth/me')
      } catch (error) {
        if (error instanceof ApiError && error.status === 401) return null
        throw error
      }
    },
    retry: false,
    staleTime: Infinity,
  })
}

export function useLogin() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (request: LoginRequest) =>
      apiFetch<CurrentUser>('/auth/login', { method: 'POST', body: request }),
    onSuccess: (user) => queryClient.setQueryData(CURRENT_USER_KEY, user),
  })
}

export function useLogout() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => apiFetch<void>('/auth/logout', { method: 'POST' }),
    onSuccess: () => {
      queryClient.clear()
      queryClient.setQueryData(CURRENT_USER_KEY, null)
    },
  })
}

export interface ChangePasswordRequest {
  currentPassword: string
  newPassword: string
}

/** Replaces the initial password; on success `/auth/me` is refetched so the gate drops the setup step. */
export function useChangePassword() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (request: ChangePasswordRequest) =>
      apiFetch<void>('/auth/password', { method: 'POST', body: request }),
    // Not awaited: the mutation reports success right away, the gate swaps pages once /me refetched.
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: CURRENT_USER_KEY })
    },
  })
}

/** Always 202 for any username, so the response never reveals whether the account exists. */
export function useRequestPasswordReset() {
  return useMutation({
    mutationFn: (request: { username: string }) =>
      apiFetch<void>('/auth/password-reset/request', { method: 'POST', body: request }),
  })
}

export interface ConfirmPasswordResetRequest {
  username: string
  code: string
  newPassword: string
}

/** 204 on success; 400 INVALID_RESET_CODE or PASSWORD_POLICY otherwise. */
export function useConfirmPasswordReset() {
  return useMutation({
    mutationFn: (request: ConfirmPasswordResetRequest) =>
      apiFetch<void>('/auth/password-reset/confirm', { method: 'POST', body: request }),
  })
}
