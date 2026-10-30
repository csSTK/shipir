import i18n from 'i18next'
import { ApiError } from '../../../shared/api/client.ts'

/** `ApiError.message` is the raw response body; the validator's text is its `message` field. */
function serverMessage(error: ApiError): string | null {
  try {
    const parsed: unknown = JSON.parse(error.message)
    if (parsed && typeof parsed === 'object' && 'message' in parsed && typeof parsed.message === 'string' && parsed.message.trim()) return parsed.message
  } catch {
    // not JSON: fall through to the generic message
  }
  return null
}

/** Plain-language message for a failed save or restore; the validator's own message is shown as the server gave it. */
export function describeConfigError(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 403) return i18n.t('domains:ProxyConfigEditor.forbidden')
    switch (error.code) {
      case 'INVALID_PROXY_CONFIG':
        return serverMessage(error) ?? i18n.t('domains:ProxyConfigEditor.invalid')
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
