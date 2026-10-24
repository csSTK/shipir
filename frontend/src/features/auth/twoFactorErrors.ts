import { ApiError } from '../../shared/api/client.ts'

/** User-facing text for 2FA API errors; unknown errors get `fallback`. */
export function twoFactorErrorMessage(error: unknown, fallback: string): string {
  if (error instanceof ApiError) {
    switch (error.code) {
      case 'INVALID_CODE':
        return 'That code is not valid. Check your authenticator app and try again.'
      case 'TOO_MANY_ATTEMPTS':
        return 'Too many attempts. Wait a few minutes and try again.'
      case 'NO_SETUP_IN_PROGRESS':
        return 'The setup expired. Start again.'
      case 'TWO_FACTOR_ALREADY_ENABLED':
        return 'Two-factor authentication is already enabled.'
      case 'ENCRYPTION_KEY_MISSING':
        return 'The server has no encryption key configured, so two-factor authentication cannot be enabled.'
      case 'INVALID_CURRENT_PASSWORD':
        return 'The password is incorrect.'
      case 'TWO_FACTOR_REQUIRED':
        return 'Two-factor authentication is required for administrators and cannot be disabled.'
    }
  }
  return fallback
}
