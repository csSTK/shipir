import { useQuery } from '@tanstack/react-query'
import i18n from 'i18next'
import { ApiError, apiFetch } from '../../shared/api/client.ts'
import { throttleMessage } from './throttleErrors.ts'

/** What the user sends to repeat their authentication; SSO users send nothing (an empty object). */
export interface StepUpCredentials {
  password?: string
  code?: string
}

/** `GET /api/auth/step-up`: which kind of confirmation the signed-in user has to give. */
export interface StepUpStatus {
  method: 'PASSWORD' | 'SSO'
  totpRequired: boolean
  /** SSO only: the provider's sign-in is recent enough already. */
  fresh: boolean
  windowSeconds: number
}

/** Always fetched anew: a stale answer ("fresh") would send the user into a refusal. */
export function useStepUpStatus() {
  return useQuery({
    queryKey: ['auth', 'step-up'],
    queryFn: () => apiFetch<StepUpStatus>('/auth/step-up'),
    gcTime: 0,
    staleTime: 0,
    retry: false,
  })
}

/** True for any refusal that comes from the step-up check (wrong or missing credentials, stale SSO, token caller, lockout `STEP_UP_THROTTLED`); other 429s are not. */
export function isStepUpError(error: unknown): error is ApiError {
  return error instanceof ApiError && (error.code?.startsWith('STEP_UP_') ?? false)
}

export function isSsoStepUpRequired(error: unknown): boolean {
  return error instanceof ApiError && error.code === 'STEP_UP_SSO_REQUIRED'
}

/** User-facing text for a step-up refusal; `null` if the error is not one. Lockouts (429) use the shared throttle wording. */
export function stepUpErrorMessage(error: unknown): string | null {
  if (!isStepUpError(error)) return null
  const throttled = throttleMessage(error)
  if (throttled !== null) return throttled
  switch (error.code) {
    case 'STEP_UP_INVALID':
      return i18n.t('auth:StepUpDialog.thePasswordOrCodeIs')
    case 'STEP_UP_REQUIRED':
      return i18n.t('auth:StepUpDialog.enterYourPassword')
    case 'STEP_UP_SSO_REQUIRED':
      return i18n.t('auth:StepUpDialog.signInAgainWithSso')
    case 'STEP_UP_SESSION_REQUIRED':
      return i18n.t('auth:StepUpDialog.notAvailableForTokens')
    default:
      return null
  }
}

/** Sends the browser to the identity provider to log in again; it returns to the current page afterwards. */
export function startSsoReauth(): void {
  const { pathname, search, hash } = window.location
  const here = pathname + search + hash
  window.location.assign(`/api/auth/oidc/reauthorize?return=${encodeURIComponent(here)}`)
}
