import i18n from 'i18next'
import { ApiError } from '../../../shared/api/client.ts'

/** Plain-language message for a failed save or restore; the validator's own message is shown as the server gave it. */
export function describeConfigError(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 403) return i18n.t('domains:ProxyConfigEditor.forbidden')
    switch (error.code) {
      case 'INVALID_PROXY_CONFIG':
        return error.serverMessage ?? i18n.t('domains:ProxyConfigEditor.invalid')
      case 'PROXY_SYNC_FAILED':
        return i18n.t('domains:ProxyConfigEditor.syncFailed')
      case 'PROXY_CONFIG_VERSION_NOT_FOUND':
        return i18n.t('domains:ProxyConfigEditor.versionGone')
      case 'PROXY_NOT_FOUND':
      case 'PROXY_NOT_SET_UP':
        return i18n.t('domains:ProxyConfigEditor.notSetUp')
    }
  }
  return i18n.t('domains:ProxyConfigEditor.failed')
}
