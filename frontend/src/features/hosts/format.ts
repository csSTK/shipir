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
}

export function describeError(code: string | null): string {
  return (code && ERROR_MESSAGES[code]) || 'The environment is not reachable.'
}
