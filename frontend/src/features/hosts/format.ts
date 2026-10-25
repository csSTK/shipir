import { ApiError } from '../../shared/api/client.ts'

const GIB = 1024 ** 3
const MIB = 1024 ** 2

export function formatMemory(bytes: number): string {
  if (bytes >= GIB) return `${trim(bytes / GIB)} GiB`
  return `${trim(bytes / MIB)} MiB`
}

function trim(value: number): string {
  return String(Math.round(value * 10) / 10)
}

export function plural(count: number, singular: string, pluralForm = `${singular}s`): string {
  return `${count} ${count === 1 ? singular : pluralForm}`
}

/** Short, human explanation for the error codes of the overview endpoint. */
const ERROR_MESSAGES: Record<string, string> = {
  UNREACHABLE: 'Could not reach the Docker host.',
  TIMEOUT: 'The connection timed out.',
  TLS_ERROR: 'The TLS handshake failed.',
  PERMISSION_DENIED: 'Permission denied for this Docker endpoint.',
  INVALID_TLS_MATERIAL: 'The stored certificate or key is invalid.',
  DECRYPTION_FAILED: 'The stored certificates could not be decrypted.',
  ENCRYPTION_KEY_MISSING: 'The server has no encryption key configured.',
  HOST_NOT_FOUND: 'This environment does not exist or was deleted.',
}

export function describeError(code: string | null): string {
  return (code && ERROR_MESSAGES[code]) || 'The environment is not reachable.'
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
