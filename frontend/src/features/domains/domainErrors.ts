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
      case 'DNS_CREDENTIALS_REQUIRED':
        return i18n.t('domains:errors.dnsCredentialsRequired')
      case 'INVALID_DNS_PROVIDER':
        return i18n.t('domains:ProxySettingsPanel.providerInvalid')
      case 'INVALID_DNS_CREDENTIALS':
        return i18n.t('domains:ProxySettingsPanel.variableInvalid')
      case 'PROXY_DEPLOYMENT_MISSING':
        return i18n.t('domains:errors.deploymentMissing')
      case 'DNS_IN_USE':
        return i18n.t('domains:errors.dnsInUse')
      case 'ENCRYPTION_KEY_MISSING':
        return i18n.t('domains:errors.noEncryptionKey')
    }
  }
  return i18n.t('domains:errors.generic')
}
