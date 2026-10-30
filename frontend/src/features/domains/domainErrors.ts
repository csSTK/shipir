import { ApiError } from '../../shared/api/client.ts'
import i18n from 'i18next'

/** Maps proxy API failures to short, plain-language messages. */
export function describeProxyError(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 403) return i18n.t('domains:errors.forbidden')
    switch (error.code) {
      case 'PROXY_EXISTS':
        return i18n.t('domains:errors.proxyExists')
      case 'PROXY_SETUP_BLOCKED':
        return i18n.t('domains:errors.setupBlocked')
      case 'UNSUPPORTED_FOR_PROXY':
        return i18n.t('domains:errors.unsupported')
      case 'INVALID_EMAIL':
        return i18n.t('domains:errors.invalidEmail')
      case 'ENCRYPTION_KEY_MISSING':
        return i18n.t('domains:errors.noEncryptionKey')
    }
  }
  return i18n.t('domains:errors.generic')
}
