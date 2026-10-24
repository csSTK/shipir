import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ApiError, apiFetch } from '../../shared/api/client.ts'

export type SetupStep = 'PASSWORD' | 'TWO_FACTOR' | 'ENVIRONMENT'

export interface CurrentUser {
  username: string
  role: 'ADMIN' | 'OPERATOR' | 'VIEWER'
  setupRequired: boolean
  /** Which first-start step is pending; `null` once setup is complete. */
  setupStep?: SetupStep | null
  twoFactorEnabled?: boolean
  /** Issuer of the linked single sign-on identity; null/absent for local accounts. */
  externalIssuer?: string | null
}

/** Login answer for a user with 2FA: no session yet, a code is needed. */
export interface MfaRequired {
  mfaRequired: true
}

export function isMfaRequired(result: CurrentUser | MfaRequired | undefined): result is MfaRequired {
  return !!result && 'mfaRequired' in result
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
      apiFetch<CurrentUser | MfaRequired>('/auth/login', { method: 'POST', body: request }),
    // With 2FA there is no session yet: the second step finishes the sign-in.
    onSuccess: (result) => {
      if (!isMfaRequired(result)) queryClient.setQueryData(CURRENT_USER_KEY, result)
    },
  })
}

/** Second sign-in step: TOTP code (6 digits) or recovery code (XXXXX-XXXXX). */
export function useVerifyTwoFactor() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (code: string) =>
      apiFetch<CurrentUser>('/auth/2fa/verify', { method: 'POST', body: { code } }),
    onSuccess: (user) => queryClient.setQueryData(CURRENT_USER_KEY, user),
  })
}

export interface TwoFactorSetup {
  secret: string
  otpauthUri: string
}

/** Starts enrolment; the secret lives only in the caller's state until confirmed. */
export function useTwoFactorSetup() {
  return useMutation({
    mutationFn: () => apiFetch<TwoFactorSetup>('/auth/2fa/setup', { method: 'POST' }),
  })
}

export function useConfirmTwoFactor() {
  return useMutation({
    mutationFn: (code: string) =>
      apiFetch<{ recoveryCodes: string[] }>('/auth/2fa/confirm', { method: 'POST', body: { code } }),
  })
}

export function useDisableTwoFactor() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (request: { password: string; code: string }) =>
      apiFetch<void>('/auth/2fa/disable', { method: 'POST', body: request }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: CURRENT_USER_KEY })
    },
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

export interface OidcPublicConfig {
  enabled: boolean
  /** Provider host shown on the button; null while single sign-on is off. */
  displayName: string | null
  localLoginEnabled: boolean
}

const OIDC_DEFAULT: OidcPublicConfig = { enabled: false, displayName: null, localLoginEnabled: true }

/** Public. Any failure falls back to plain local login so the login page never breaks. */
export function useOidcConfig() {
  return useQuery({
    queryKey: ['auth', 'oidc-config'],
    queryFn: async () => {
      try {
        return (await apiFetch<OidcPublicConfig>('/auth/oidc/config')) ?? OIDC_DEFAULT
      } catch {
        return OIDC_DEFAULT
      }
    },
    retry: false,
  })
}
