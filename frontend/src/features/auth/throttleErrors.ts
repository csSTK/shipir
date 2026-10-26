import { ApiError } from '../../shared/api/client.ts'
import i18n from 'i18next'

/** Text for a throttled (429) auth request, naming the wait from `Retry-After`; `null` for any other error. */
export function throttleMessage(error: unknown): string | null {
  if (!(error instanceof ApiError) || error.status !== 429) return null
  const seconds = error.retryAfterSeconds
  if (seconds === undefined) return i18n.t('auth:throttleErrors.tooManyAttemptsTryAgain')
  return i18n.t('auth:throttleErrors.tryAgainIn', { count: seconds })
}
