import i18n from 'i18next'
import { ApiError } from '../../../shared/api/client.ts'

export type CertificateField = 'name' | 'cert' | 'key' | 'form'

export interface CertificateError {
  field: CertificateField
  message: string
}

/** Maps certificate API failures to a plain-language message and the form field it belongs to. */
export function describeCertificateError(error: unknown): CertificateError {
  if (error instanceof ApiError) {
    if (error.status === 403) return { field: 'form', message: i18n.t('domains:certErrors.forbidden') }
    switch (error.code) {
      case 'INVALID_NAME':
        return { field: 'name', message: i18n.t('domains:certErrors.invalidName') }
      case 'CERT_NAME_TAKEN':
        return { field: 'name', message: i18n.t('domains:certErrors.nameTaken') }
      case 'INVALID_CERTIFICATE':
        return { field: 'cert', message: i18n.t('domains:certErrors.invalidCertificate') }
      case 'CERTIFICATE_EXPIRED':
        return { field: 'cert', message: i18n.t('domains:certErrors.expired') }
      case 'CERTIFICATE_NOT_YET_VALID':
        return { field: 'cert', message: i18n.t('domains:certErrors.notYetValid') }
      case 'CERT_DOES_NOT_COVER_HOST':
        return { field: 'cert', message: i18n.t('domains:certErrors.doesNotCover') }
      case 'INVALID_KEY':
        return { field: 'key', message: i18n.t('domains:certErrors.invalidKey') }
      case 'KEY_ENCRYPTED':
        return { field: 'key', message: i18n.t('domains:certErrors.keyEncrypted') }
      case 'KEY_MISMATCH':
        return { field: 'key', message: i18n.t('domains:certErrors.keyMismatch') }
      case 'CERT_TOO_LARGE':
        return { field: 'form', message: i18n.t('domains:certErrors.tooLarge') }
      case 'CERTIFICATE_IN_USE':
        return { field: 'form', message: i18n.t('domains:certErrors.inUse', { routes: (error.details ?? []).join(', ') }) }
      case 'CERT_NOT_FOUND':
        return { field: 'form', message: i18n.t('domains:certErrors.notFound') }
      case 'ENCRYPTION_KEY_MISSING':
        return { field: 'form', message: i18n.t('domains:errors.noEncryptionKey') }
    }
  }
  return { field: 'form', message: i18n.t('domains:certErrors.generic') }
}
