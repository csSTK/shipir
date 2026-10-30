import type { CertMode } from '../api.ts'
import { checkHostname } from './hostname.ts'
import type { BackendScheme, ProxyRoute, RouteRequest } from './api.ts'

export interface UserRow {
  key: number
  name: string
  /** Write-only: always empty for stored users; the server keeps the stored hash for an empty password. */
  password: string
  existing: boolean
}

export interface HeaderRow {
  key: number
  name: string
  value: string
}

/** Form state of the advanced route options. `certMode` '' means "the proxy's default" (new routes only). */
export interface AdvancedState {
  aliases: string[]
  pathPrefix: string
  backendScheme: BackendScheme
  entrypoint: string
  certMode: CertMode | ''
  certificateId: string
  redirectHttps: boolean
  users: UserRow[]
  headers: HeaderRow[]
}

export type AdvancedPayload = Pick<RouteRequest, 'aliases' | 'backendScheme' | 'pathPrefix' | 'entrypoint' | 'certMode' | 'certificateId' | 'redirectHttps' | 'responseHeaders'> & {
  basicAuth?: Record<string, string>
}

export const MAX_ENTRIES = 20
const PATH_PREFIX = /^(\/|\/[A-Za-z0-9._~-]+(\/[A-Za-z0-9._~-]+)*\/?)$/
const USER_NAME = /^[A-Za-z0-9._-]{1,64}$/
const HEADER_NAME = /^[A-Za-z0-9-]{1,64}$/
const HEADER_VALUE = /^[\x20-\x7E]{0,512}$/
const HEADER_VALUE_FORBIDDEN = /["\\`${}<>]/

let nextKey = 1
export const newKey = () => nextKey++

export function initialAdvanced(route?: ProxyRoute): AdvancedState {
  return {
    aliases: route?.aliases ?? [],
    pathPrefix: route?.pathPrefix ?? '',
    backendScheme: route?.backendScheme ?? 'HTTP',
    entrypoint: route?.entrypoint ?? '',
    certMode: route?.certMode ?? '',
    certificateId: route?.certificateId ?? '',
    redirectHttps: route?.redirectHttps ?? true,
    users: (route?.basicAuthUsers ?? []).map((name) => ({ key: newKey(), name, password: '', existing: true })),
    headers: Object.entries(route?.responseHeaders ?? {}).map(([name, value]) => ({ key: newKey(), name, value })),
  }
}

const trimmed = (list: string[]) => list.map((a) => a.trim()).filter(Boolean)
const headerMap = (rows: HeaderRow[]) => Object.fromEntries(rows.filter((h) => h.name.trim()).map((h) => [h.name.trim(), h.value]))
const usersSignature = (rows: UserRow[]) => rows.map((u) => `${u.name.trim()}\u0000${u.password}`).join('\u0001')

/**
 * The advanced part of the request body. A new route sends only fields that differ from the defaults; an edit sends
 * all of them, because PUT replaces everything but the container, port, certificate mode and basic auth. `basicAuth`
 * is sent only when the users were touched (omitted keeps the stored users; an empty password keeps that user's hash).
 */
export function advancedPayload(state: AdvancedState, initial: AdvancedState, editing: boolean): AdvancedPayload {
  const out: AdvancedPayload = {}
  const aliases = trimmed(state.aliases)
  if (editing || aliases.length > 0) out.aliases = aliases
  if (editing || state.backendScheme !== 'HTTP') out.backendScheme = state.backendScheme
  const path = state.pathPrefix.trim()
  if (editing) out.pathPrefix = path || null
  else if (path) out.pathPrefix = path
  const entrypoint = state.entrypoint.trim()
  if (editing) out.entrypoint = entrypoint || null
  else if (entrypoint) out.entrypoint = entrypoint
  if (state.certMode !== '' && (editing || state.certMode !== initial.certMode)) out.certMode = state.certMode
  if (state.certMode === 'CUSTOM') out.certificateId = state.certificateId
  else if (editing) out.certificateId = null
  if (editing || !state.redirectHttps) out.redirectHttps = state.redirectHttps
  const headers = headerMap(state.headers)
  if (editing || Object.keys(headers).length > 0) out.responseHeaders = headers
  if (usersSignature(state.users) !== usersSignature(initial.users)) {
    out.basicAuth = Object.fromEntries(state.users.filter((u) => u.name.trim()).map((u) => [u.name.trim(), u.password]))
  }
  return out
}

export type AdvancedField = 'aliases' | 'pathPrefix' | 'certificateId' | 'users' | 'headers'
/** Field -> key of the message in the `domains` namespace (`PublishDialog.advanced.problem.<key>`). */
export type AdvancedErrors = Partial<Record<AdvancedField, string>>

/** Client-side checks mirroring the server's validator. Returns the problem per field; empty when fine. */
export function checkAdvanced(state: AdvancedState, options: { allowWildcard: boolean }): AdvancedErrors {
  const errors: AdvancedErrors = {}
  const aliases = trimmed(state.aliases)
  if (aliases.length > MAX_ENTRIES) errors.aliases = 'tooMany'
  else {
    const bad = aliases.map((a) => checkHostname(a, options.allowWildcard)).find((p) => p !== null)
    if (bad) errors.aliases = bad === 'wildcardNotAllowed' ? 'aliasWildcard' : 'aliasInvalid'
  }
  const path = state.pathPrefix.trim()
  if (path && (path.length > 255 || !PATH_PREFIX.test(path) || path.split('/').some((s) => s === '.' || s === '..'))) errors.pathPrefix = 'pathInvalid'
  if (state.certMode === 'CUSTOM' && !state.certificateId) errors.certificateId = 'certificateRequired'
  const users = state.users.filter((u) => u.name.trim() || u.password)
  if (users.length > MAX_ENTRIES) errors.users = 'tooMany'
  else if (users.some((u) => !USER_NAME.test(u.name.trim()))) errors.users = 'userInvalid'
  else if (users.some((u) => !u.existing && !u.password)) errors.users = 'passwordRequired'
  else if (new Set(users.map((u) => u.name.trim())).size !== users.length) errors.users = 'userDuplicate'
  const headers = state.headers.filter((h) => h.name.trim() || h.value)
  if (headers.length > MAX_ENTRIES) errors.headers = 'tooMany'
  else if (headers.some((h) => !HEADER_NAME.test(h.name.trim()) || !HEADER_VALUE.test(h.value) || HEADER_VALUE_FORBIDDEN.test(h.value))) errors.headers = 'headerInvalid'
  else if (new Set(headers.map((h) => h.name.trim().toLowerCase())).size !== headers.length) errors.headers = 'headerDuplicate'
  return errors
}
