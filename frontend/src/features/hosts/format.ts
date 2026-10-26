import { ApiError } from '../../shared/api/client.ts'
import i18n from 'i18next'
import { formatNumber } from '../../shared/i18n/format.ts'

const GIB = 1024 ** 3
const MIB = 1024 ** 2

export function formatMemory(bytes: number): string {
  if (bytes >= GIB) return `${trim(bytes / GIB)} GiB`
  return `${trim(bytes / MIB)} MiB`
}

function trim(value: number): string {
  return formatNumber(value, { maximumFractionDigits: 1, useGrouping: false })
}

export type CountNoun = 'cpu' | 'image' | 'volume' | 'container' | 'entry'

/** "1 image" / "3 images": the plural forms live in the hosts namespace (`count.<noun>_one` / `_other`). */
export function countOf(count: number, noun: CountNoun): string {
  return i18n.t(`hosts:count.${noun}`, { count })
}

/** Short, human explanation for the error codes of the overview endpoint. */
const ERROR_MESSAGES: Record<string, string> = {
  get UNREACHABLE() { return i18n.t('hosts:format.couldNotReachTheDocker') },
  get TIMEOUT() { return i18n.t('hosts:format.theConnectionTimedOut') },
  get TLS_ERROR() { return i18n.t('hosts:format.theTlsHandshakeFailed') },
  get PERMISSION_DENIED() { return i18n.t('hosts:format.permissionDeniedForThisDocker') },
  get INVALID_TLS_MATERIAL() { return i18n.t('hosts:format.theStoredCertificateOrKey') },
  get DECRYPTION_FAILED() { return i18n.t('hosts:format.theStoredCertificatesCouldNot') },
  get ENCRYPTION_KEY_MISSING() { return i18n.t('hosts:format.theServerHasNoEncryption') },
  get HOST_NOT_FOUND() { return i18n.t('hosts:format.thisEnvironmentDoesNotExist') },
}

export function describeError(code: string | null): string {
  return (code && ERROR_MESSAGES[code]) || i18n.t('hosts:format.theEnvironmentIsNotReachable')
}

/** Message for a failed resource request (`/api/hosts/{id}/...`): maps the 404/502 error code. */
export function describeResourceError(error: unknown): string {
  return describeError(error instanceof ApiError ? (error.code ?? null) : null)
}

const TIB = 1024 ** 4
const PIB = 1024 ** 5

/** Byte size with one decimal at most: B, KiB, MiB, GiB, TiB, and PiB for anything larger. */
export function formatSize(bytes: number): string {
  if (bytes >= PIB) return `${trim(bytes / PIB)} PiB`
  if (bytes >= TIB) return `${trim(bytes / TIB)} TiB`
  if (bytes >= GIB) return `${trim(bytes / GIB)} GiB`
  if (bytes >= MIB) return `${trim(bytes / MIB)} MiB`
  if (bytes >= 1024) return `${trim(bytes / 1024)} KiB`
  return `${bytes} B`
}
