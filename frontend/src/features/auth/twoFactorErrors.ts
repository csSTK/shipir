import { ApiError } from '../../shared/api/client.ts'
import i18n from 'i18next'
import { throttleMessage } from './throttleErrors.ts'

/** User-facing text for 2FA API errors; unknown errors get `fallback`. Throttling (429) uses the shared `throttleMessage` wording. */
export function twoFactorErrorMessage(error: unknown, fallback: string): string {
  const throttled = throttleMessage(error)
  if (throttled !== null) return throttled
  if (error instanceof ApiError) {
    switch (error.code) {
      case 'INVALID_CODE':
        return i18n.t('auth:twoFactorErrors.thatCodeIsNotValid')
      case 'NO_SETUP_IN_PROGRESS':
        return i18n.t('auth:twoFactorErrors.theSetupExpiredStartAgain')
      case 'TWO_FACTOR_ALREADY_ENABLED':
        return i18n.t('auth:twoFactorErrors.twoFactorAuthenticationIsAlready')
      case 'ENCRYPTION_KEY_MISSING':
        return i18n.t('auth:twoFactorErrors.theServerHasNoEncryption')
      case 'INVALID_CURRENT_PASSWORD':
        return i18n.t('auth:twoFactorErrors.thePasswordIsIncorrect')
      case 'TWO_FACTOR_REQUIRED':
        return i18n.t('auth:twoFactorErrors.twoFactorAuthenticationIsRequired')
    }
  }
  return fallback
}
