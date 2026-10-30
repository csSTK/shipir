/** Mirrors the backend rule (`DnsNames.isValidHostname`): lower-case DNS name, 2+ labels, labels of 1-63 characters, 253 characters at most. */
const HOSTNAME = /^(?=.{1,253}$)[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$/

export type HostnameProblem = 'required' | 'tooLong' | 'uppercase' | 'wildcardNotAllowed' | 'invalid'

/**
 * Checks a domain name the way the server does. A leading `*.` is a wildcard and only allowed when the certificate
 * mode can cover it (`allowWildcard`). Returns null when the name is fine.
 */
export function checkHostname(raw: string, allowWildcard = false): HostnameProblem | null {
  const value = raw.trim()
  if (!value) return 'required'
  if (value.length > 253) return 'tooLong'
  const wildcard = value.startsWith('*.')
  const base = wildcard ? value.slice(2) : value
  if (!HOSTNAME.test(base)) return base !== base.toLowerCase() && HOSTNAME.test(base.toLowerCase()) ? 'uppercase' : 'invalid'
  if (wildcard && !allowWildcard) return 'wildcardNotAllowed'
  return null
}
