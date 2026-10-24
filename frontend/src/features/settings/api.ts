import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ApiError, apiFetch, readCookie } from '../../shared/api/client.ts'
import type { ChatSession, ChatSessionDetail } from '../ai-chat/api.ts'
import { NOTIFICATION_TYPE_LABELS, type NotificationType } from '../notifications/api.ts'

export type WebhookEventType = NotificationType

export const WEBHOOK_EVENT_LABELS = NOTIFICATION_TYPE_LABELS

/** The full URL is never returned by the API, only a hint. */
export interface Webhook {
  id: string
  name: string
  urlHint: string
  eventTypes: WebhookEventType[]
  enabled: boolean
  lastDeliveryAt: string | null
  /** `SUCCESS`, `HTTP_<code>`, `TIMEOUT` or `FAILED`; null when never delivered. */
  lastDeliveryStatus: string | null
}

/** `url` is omitted on update to keep the stored one. */
export interface WebhookInput {
  name: string
  url?: string
  eventTypes: WebhookEventType[]
  enabled: boolean
}

/** `status` is `SUCCESS`, `HTTP_<code>`, `TIMEOUT` or `FAILED`. */
export interface WebhookTestResult {
  ok: boolean
  status: string
}

export const WEBHOOKS_KEY = ['notification-webhooks'] as const
const BASE = '/notifications/webhooks'

export function useWebhooks() {
  return useQuery({ queryKey: WEBHOOKS_KEY, queryFn: () => apiFetch<Webhook[]>(BASE) })
}

function useInvalidate() {
  const queryClient = useQueryClient()
  return () => queryClient.invalidateQueries({ queryKey: WEBHOOKS_KEY })
}

export function useCreateWebhook() {
  const invalidate = useInvalidate()
  return useMutation({
    mutationFn: (input: WebhookInput) => apiFetch<Webhook>(BASE, { method: 'POST', body: input }),
    onSuccess: () => void invalidate(),
  })
}

export function useUpdateWebhook() {
  const invalidate = useInvalidate()
  return useMutation({
    mutationFn: ({ id, input }: { id: string; input: WebhookInput }) =>
      apiFetch<Webhook>(`${BASE}/${id}`, { method: 'PUT', body: input }),
    onSuccess: () => void invalidate(),
  })
}

export function useDeleteWebhook() {
  const invalidate = useInvalidate()
  return useMutation({
    mutationFn: (id: string) => apiFetch<void>(`${BASE}/${id}`, { method: 'DELETE' }),
    onSuccess: () => void invalidate(),
  })
}

export function useTestWebhook() {
  const invalidate = useInvalidate()
  return useMutation({
    mutationFn: (id: string) => apiFetch<WebhookTestResult>(`${BASE}/${id}/test`, { method: 'POST' }),
    onSettled: () => void invalidate(),
  })
}

/** Retention periods in days, 1 to 3650. Purge runs once a day on the server. */
export interface RetentionSettings {
  auditLogDays: number
  notificationDays: number
  chatSessionDays: number
}

export const RETENTION_KEY = ['settings-retention'] as const
const RETENTION_PATH = '/settings/retention'

export function useRetentionSettings() {
  return useQuery({ queryKey: RETENTION_KEY, queryFn: () => apiFetch<RetentionSettings>(RETENTION_PATH) })
}

export function useSaveRetentionSettings() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (input: RetentionSettings) =>
      apiFetch<RetentionSettings>(RETENTION_PATH, { method: 'PUT', body: input }),
    onSuccess: (saved) => queryClient.setQueryData(RETENTION_KEY, saved),
  })
}

export type AiProvider = 'OPENAI_COMPATIBLE' | 'GEMINI'

/** The API key is never returned, only whether one is stored and a hint. */
export interface AiProviderConfig {
  provider: AiProvider
  baseUrl: string
  model: string
  keyConfigured: boolean
  apiKeyHint: string | null
}

/** `providers` always lists both providers, OpenAI-compatible first. */
export interface AiSettings {
  activeProvider: AiProvider
  enabled: boolean
  providers: AiProviderConfig[]
}

/** `provider` is the config being edited; `apiKey` omitted keeps its stored key, an empty string clears it. */
export interface AiSettingsInput {
  enabled: boolean
  activeProvider: AiProvider
  provider: AiProvider
  baseUrl: string
  model: string
  apiKey?: string
}

export type AiConnectionStatus = 'OK' | 'AUTH_FAILED' | 'UNREACHABLE'

export const AI_SETTINGS_KEY = ['settings-ai'] as const
const AI_PATH = '/settings/ai'

export function useAiSettings() {
  return useQuery({ queryKey: AI_SETTINGS_KEY, queryFn: () => apiFetch<AiSettings>(AI_PATH) })
}

export function useSaveAiSettings() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (input: AiSettingsInput) => apiFetch<AiSettings>(AI_PATH, { method: 'PUT', body: input }),
    onSuccess: (saved) => queryClient.setQueryData(AI_SETTINGS_KEY, saved),
  })
}

/** Tests the stored settings of one provider, not unsaved form values. */
export function useTestAiConnection() {
  return useMutation({
    mutationFn: (provider: AiProvider) =>
      apiFetch<{ status: AiConnectionStatus }>(`${AI_PATH}/test`, { method: 'POST', body: { provider } }),
  })
}

export type OidcRole = 'ADMIN' | 'OPERATOR' | 'VIEWER'

/** The client secret is never returned, only whether one is stored and a masked hint. */
export interface OidcSettings {
  issuerUrl: string | null
  clientId: string | null
  clientSecretConfigured: boolean
  clientSecretHint: string | null
  scopes: string
  groupsClaim: string
  roleMapping: Partial<Record<OidcRole, string[]>>
  autoProvision: boolean
  localLoginEnabled: boolean
  enabled: boolean
  /** What to register with the provider, computed by the backend. */
  redirectUri: string
}

/** `clientSecret` omitted keeps the stored secret, an empty string clears it. A blank issuer switches single sign-on off. */
export interface OidcSettingsInput {
  issuerUrl: string
  clientId: string
  clientSecret?: string
  scopes: string
  groupsClaim: string
  roleMapping: Record<OidcRole, string[]>
  autoProvision: boolean
  localLoginEnabled: boolean
}

export type OidcTestStatus = 'OK' | 'UNREACHABLE' | 'INVALID'

export const OIDC_SETTINGS_KEY = ['settings-oidc'] as const
const OIDC_PATH = '/settings/oidc'

export function useOidcSettings() {
  return useQuery({ queryKey: OIDC_SETTINGS_KEY, queryFn: () => apiFetch<OidcSettings>(OIDC_PATH) })
}

export function useSaveOidcSettings() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (input: OidcSettingsInput) => apiFetch<OidcSettings>(OIDC_PATH, { method: 'PUT', body: input }),
    onSuccess: (saved) => {
      queryClient.setQueryData(OIDC_SETTINGS_KEY, saved)
      void queryClient.invalidateQueries({ queryKey: ['auth', 'oidc-config'] })
    },
  })
}

/** Runs discovery against the stored issuer, not unsaved form values. */
export function useTestOidcConnection() {
  return useMutation({
    mutationFn: () => apiFetch<{ status: OidcTestStatus }>(`${OIDC_PATH}/test`, { method: 'POST' }),
  })
}

export const AUDIT_ACTION_LABELS: Record<string, string> = {
  LOGIN: 'Login',
  LOGOUT: 'Logout',
  PASSWORD_CHANGE: 'Password changed',
  PASSWORD_RESET_CONFIRM: 'Password reset',
  HOST_CREATE: 'Host created',
  HOST_RENAME: 'Host renamed',
  HOST_DELETE: 'Host deleted',
  WEBHOOK_CREATE: 'Webhook created',
  WEBHOOK_UPDATE: 'Webhook updated',
  WEBHOOK_DELETE: 'Webhook deleted',
  DEPLOYMENT_CREATE: 'Deployment created',
  DEPLOYMENT_UPDATE: 'Deployment updated',
  DEPLOYMENT_DELETE: 'Deployment deleted',
  DEPLOYMENT_DEPLOY: 'Deployment deployed',
  DEPLOYMENT_STOP: 'Deployment stopped',
  DEPLOYMENT_ROLLBACK: 'Deployment rolled back',
  DEPLOYMENT_DEPLOY_RESULT: 'Deployment deploy result',
  DEPLOYMENT_ROLLBACK_RESULT: 'Deployment rollback result',
  DEPLOYMENT_GIT_REDEPLOY: 'Deployment pulled and redeployed',
  DEPLOYMENT_GIT_REDEPLOY_RESULT: 'Deployment redeploy result',
  DEPLOYMENT_AUTO_UPDATE: 'Deployment auto-updated',
  DEPLOYMENT_AUTO_UPDATE_RESULT: 'Deployment auto-update result',
  DEPLOYMENT_AUTO_UPDATE_CONFIG: 'Deployment auto-update configured',
  // Historic rows written before the stack -> deployment rename keep their old action names.
  STACK_CREATE: 'Stack created',
  STACK_UPDATE: 'Stack updated',
  STACK_DELETE: 'Stack deleted',
  STACK_DEPLOY: 'Stack deployed',
  STACK_STOP: 'Stack stopped',
  STACK_ROLLBACK: 'Stack rolled back',
  STACK_DEPLOY_RESULT: 'Stack deploy result',
  STACK_ROLLBACK_RESULT: 'Stack rollback result',
  STACK_GIT_REDEPLOY: 'Stack pulled and redeployed',
  STACK_GIT_REDEPLOY_RESULT: 'Stack redeploy result',
  STACK_AUTO_UPDATE: 'Stack auto-updated',
  STACK_AUTO_UPDATE_RESULT: 'Stack auto-update result',
  STACK_AUTO_UPDATE_CONFIG: 'Stack auto-update configured',
  CONTAINER_START: 'Container started',
  CONTAINER_STOP: 'Container stopped',
  CONTAINER_RESTART: 'Container restarted',
  CONTAINER_PAUSE: 'Container paused',
  CONTAINER_UNPAUSE: 'Container unpaused',
  CONTAINER_KILL: 'Container killed',
  GIT_CREDENTIAL_CREATE: 'Git credential created',
  GIT_CREDENTIAL_UPDATE: 'Git credential updated',
  GIT_CREDENTIAL_DELETE: 'Git credential deleted',
  RETENTION_UPDATE: 'Retention updated',
  RETENTION_PURGE: 'Retention purge',
  MFA_ENABLE: 'Two-factor enabled',
  MFA_DISABLE: 'Two-factor disabled',
  MFA_VERIFY: 'Two-factor sign-in',
  MFA_RESET: 'Two-factor reset',
  SECURITY_SETTINGS_UPDATE: 'Security settings updated',
}

export interface AuditLogEntry {
  id: string
  occurredAt: string
  username: string
  action: string
  outcome: 'SUCCESS' | 'FAILURE'
  targetType: string | null
  targetId: string | null
  details: Record<string, unknown> | null
}

export interface AuditLogPageData {
  items: AuditLogEntry[]
  page: number
  size: number
  totalElements: number
}

/** `from`/`to` are inclusive ISO-8601 instants; `page` is zero-based. */
export interface AuditLogQuery {
  page: number
  size: number
  action?: string
  username?: string
  from?: string
  to?: string
}

export function useAuditLog(query: AuditLogQuery) {
  const params = new URLSearchParams({ page: String(query.page), size: String(query.size) })
  for (const key of ['action', 'username', 'from', 'to'] as const) {
    if (query[key]) params.set(key, query[key])
  }
  const path = `/audit-log?${params}`
  return useQuery({
    queryKey: ['audit-log', path],
    queryFn: () => apiFetch<AuditLogPageData>(path),
    placeholderData: (previous) => previous,
  })
}

/** The token is never returned by the API, only a hint. */
export interface GitCredential {
  id: string
  name: string
  username: string
  tokenHint: string
  createdAt: string
  updatedAt: string
}

/** `token` is required on create and omitted on update to keep the stored one. */
export interface GitCredentialInput {
  name: string
  username: string
  token?: string
}

export type GitConnectionStatus = 'OK' | 'AUTH_FAILED' | 'NOT_FOUND' | 'UNREACHABLE'

export const GIT_CREDENTIALS_KEY = ['git-credentials'] as const
const GIT_BASE = '/git/credentials'

export function useGitCredentials() {
  return useQuery({ queryKey: GIT_CREDENTIALS_KEY, queryFn: () => apiFetch<GitCredential[]>(GIT_BASE) })
}

function useInvalidateGit() {
  const queryClient = useQueryClient()
  return () => queryClient.invalidateQueries({ queryKey: GIT_CREDENTIALS_KEY })
}

export function useCreateGitCredential() {
  const invalidate = useInvalidateGit()
  return useMutation({
    mutationFn: (input: GitCredentialInput) => apiFetch<GitCredential>(GIT_BASE, { method: 'POST', body: input }),
    onSuccess: () => void invalidate(),
  })
}

export function useUpdateGitCredential() {
  const invalidate = useInvalidateGit()
  return useMutation({
    mutationFn: ({ id, input }: { id: string; input: GitCredentialInput }) =>
      apiFetch<GitCredential>(`${GIT_BASE}/${id}`, { method: 'PUT', body: input }),
    onSuccess: () => void invalidate(),
  })
}

export function useDeleteGitCredential() {
  const invalidate = useInvalidateGit()
  return useMutation({
    mutationFn: (id: string) => apiFetch<void>(`${GIT_BASE}/${id}`, { method: 'DELETE' }),
    onSuccess: () => void invalidate(),
  })
}

export function useTestGitCredential() {
  return useMutation({
    mutationFn: ({ id, repoUrl }: { id: string; repoUrl: string }) =>
      apiFetch<{ status: GitConnectionStatus }>(`${GIT_BASE}/${id}/test`, { method: 'POST', body: { repoUrl } }),
  })
}

/** Sessions users shared with admins (read-only debugging view). Owner is always filled here. */
export interface SharedChatSessionPage {
  items: ChatSession[]
  page: number
  size: number
  totalElements: number
}

export const SHARED_CHAT_SESSIONS_KEY = ['ai', 'shared-sessions'] as const

export function useSharedChatSessions(page: number, size: number) {
  return useQuery({
    queryKey: [...SHARED_CHAT_SESSIONS_KEY, page, size],
    queryFn: () => apiFetch<SharedChatSessionPage>(`/ai/shared-sessions?page=${page}&size=${size}`),
    placeholderData: (previous) => previous,
  })
}

/** Disabled while no session is selected. */
export function useSharedChatSession(id: string | null) {
  return useQuery({
    queryKey: [...SHARED_CHAT_SESSIONS_KEY, 'detail', id],
    queryFn: () => apiFetch<ChatSessionDetail>(`/ai/shared-sessions/${id}`),
    enabled: id !== null,
  })
}

export type UserRole = 'ADMIN' | 'OPERATOR' | 'VIEWER'

export interface ManagedUser {
  id: string
  username: string
  role: UserRole
  disabled: boolean
  /** True until the user has chosen their own password. */
  passwordChangeRequired: boolean
  createdAt: string
  /** Null while the user has not chosen their own password yet. */
  passwordChangedAt: string | null
  disabledAt: string | null
  twoFactorEnabled?: boolean
}

export type PermissionLevel = 'VIEW' | 'OPERATE'

/** Host grant: `hostId` only. Deployment grant: `deploymentId` + `deploymentHostId`. */
export interface PermissionGrant {
  hostId?: string | null
  deploymentId?: string | null
  deploymentHostId?: string | null
  level: PermissionLevel
}

export interface PermissionGrantInput {
  hostId?: string
  deploymentId?: string
  level: PermissionLevel
}

const permissionsPath = (userId: string) => `/users/${userId}/permissions`

export function useUserPermissions(userId: string) {
  return useQuery({
    queryKey: [...USERS_KEY, userId, 'permissions'],
    queryFn: () => apiFetch<{ grants: PermissionGrant[] }>(permissionsPath(userId)),
    gcTime: 0,
  })
}

/** Replaces the user's full grant set; an empty list revokes everything. */
export function useSaveUserPermissions() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ id, grants }: { id: string; grants: PermissionGrantInput[] }) =>
      apiFetch<{ grants: PermissionGrant[] }>(permissionsPath(id), { method: 'PUT', body: { grants } }),
    onSuccess: (_data, { id }) => qc.invalidateQueries({ queryKey: [...USERS_KEY, id, 'permissions'] }),
  })
}

/** Returned once on create and on password reset; never retrievable again. */
export interface OneTimePassword {
  id: string
  username: string
  role: UserRole
  oneTimePassword: string
}

export const USERS_KEY = ['users'] as const
const USERS_BASE = '/users'

export function useUsers() {
  return useQuery({ queryKey: USERS_KEY, queryFn: () => apiFetch<ManagedUser[]>(USERS_BASE) })
}

function useInvalidateUsers() {
  const queryClient = useQueryClient()
  return () => queryClient.invalidateQueries({ queryKey: USERS_KEY })
}

export function useCreateUser() {
  const invalidate = useInvalidateUsers()
  return useMutation({
    mutationFn: (input: { username: string; role: UserRole }) =>
      apiFetch<OneTimePassword>(USERS_BASE, { method: 'POST', body: input }),
    onSuccess: () => void invalidate(),
  })
}

export function useChangeUserRole() {
  const invalidate = useInvalidateUsers()
  return useMutation({
    mutationFn: ({ id, role }: { id: string; role: UserRole }) =>
      apiFetch<ManagedUser>(`${USERS_BASE}/${id}/role`, { method: 'PUT', body: { role } }),
    onSuccess: () => void invalidate(),
  })
}

export function useSetUserDisabled() {
  const invalidate = useInvalidateUsers()
  return useMutation({
    mutationFn: ({ id, disabled }: { id: string; disabled: boolean }) =>
      apiFetch<ManagedUser>(`${USERS_BASE}/${id}/${disabled ? 'disable' : 'enable'}`, { method: 'POST' }),
    onSuccess: () => void invalidate(),
  })
}

export function useResetUserPassword() {
  const invalidate = useInvalidateUsers()
  return useMutation({
    mutationFn: (id: string) => apiFetch<OneTimePassword>(`${USERS_BASE}/${id}/reset-password`, { method: 'POST' }),
    onSuccess: () => void invalidate(),
  })
}

export function useResetUserTwoFactor() {
  const invalidate = useInvalidateUsers()
  return useMutation({
    mutationFn: (id: string) => apiFetch<void>(`${USERS_BASE}/${id}/reset-2fa`, { method: 'POST' }),
    onSuccess: () => void invalidate(),
  })
}

export interface SecuritySettings {
  require2faForAdmins: boolean
  updatedAt: string | null
}

const SECURITY_KEY = ['settings-security'] as const
const SECURITY_PATH = '/settings/security'

export function useSecuritySettings() {
  return useQuery({ queryKey: SECURITY_KEY, queryFn: () => apiFetch<SecuritySettings>(SECURITY_PATH) })
}

export function useSaveSecuritySettings() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (input: { require2faForAdmins: boolean }) =>
      apiFetch<SecuritySettings>(SECURITY_PATH, { method: 'PUT', body: input }),
    onSuccess: (saved) => queryClient.setQueryData(SECURITY_KEY, saved),
  })
}

export function useDeleteUser() {
  const invalidate = useInvalidateUsers()
  return useMutation({
    mutationFn: (id: string) => apiFetch<void>(`${USERS_BASE}/${id}`, { method: 'DELETE' }),
    onSuccess: () => void invalidate(),
  })
}

/** The secret is never returned by the API, only a hint. */
export interface RegistryCredential {
  id: string
  name: string
  registryHost: string
  username: string
  secretHint: string
  createdAt: string
  updatedAt: string
}

/** `secret` is required on create and omitted on update to keep the stored one. */
export interface RegistryCredentialInput {
  name: string
  registryHost: string
  username: string
  secret?: string
}

export type RegistryTestStatus = 'OK' | 'AUTH_FAILED' | 'UNREACHABLE'

export const REGISTRIES_KEY = ['registries'] as const
const REGISTRIES_BASE = '/registries'

export function useRegistries() {
  return useQuery({ queryKey: REGISTRIES_KEY, queryFn: () => apiFetch<RegistryCredential[]>(REGISTRIES_BASE) })
}

function useInvalidateRegistries() {
  const queryClient = useQueryClient()
  return () => queryClient.invalidateQueries({ queryKey: REGISTRIES_KEY })
}

export function useCreateRegistry() {
  const invalidate = useInvalidateRegistries()
  return useMutation({
    mutationFn: (input: RegistryCredentialInput) =>
      apiFetch<RegistryCredential>(REGISTRIES_BASE, { method: 'POST', body: input }),
    onSuccess: () => void invalidate(),
  })
}

export function useUpdateRegistry() {
  const invalidate = useInvalidateRegistries()
  return useMutation({
    mutationFn: ({ id, input }: { id: string; input: RegistryCredentialInput }) =>
      apiFetch<RegistryCredential>(`${REGISTRIES_BASE}/${id}`, { method: 'PUT', body: input }),
    onSuccess: () => void invalidate(),
  })
}

export function useDeleteRegistry() {
  const invalidate = useInvalidateRegistries()
  return useMutation({
    mutationFn: (id: string) => apiFetch<void>(`${REGISTRIES_BASE}/${id}`, { method: 'DELETE' }),
    onSuccess: () => void invalidate(),
  })
}

export function useTestRegistry() {
  return useMutation({
    mutationFn: (id: string) =>
      apiFetch<{ status: RegistryTestStatus }>(`${REGISTRIES_BASE}/${id}/test`, { method: 'POST' }),
  })
}

export interface BackupFile {
  blob: Blob
  filename: string
}

const BACKUP_PATH = '/api/backup'
const BACKUP_FILENAME_FALLBACK = 'shipir-backup.tar.gz.enc'
const CSRF_COOKIE = 'XSRF-TOKEN'
const CSRF_HEADER = 'X-XSRF-TOKEN'

function filenameOf(disposition: string | null): string {
  const match = disposition?.match(/filename="?([^";]+)"?/i)
  return match ? match[1] : BACKUP_FILENAME_FALLBACK
}

/**
 * POST /api/backup. Not `apiFetch`: the answer is the encrypted file, not JSON.
 * Errors keep only the code and the server's detail lines, never the raw body.
 */
export async function requestBackup(passphrase: string): Promise<BackupFile> {
  const csrf = readCookie(CSRF_COOKIE)
  const response = await fetch(BACKUP_PATH, {
    method: 'POST',
    credentials: 'same-origin',
    headers: {
      Accept: 'application/octet-stream, application/json',
      'Content-Type': 'application/json',
      ...(csrf && { [CSRF_HEADER]: csrf }),
    },
    body: JSON.stringify({ passphrase }),
  })

  if (!response.ok) {
    const text = await response.text()
    let parsed: { code?: unknown; details?: unknown } = {}
    try {
      parsed = JSON.parse(text) as typeof parsed
    } catch {
      // not JSON: keep the generic error below
    }
    const details = Array.isArray(parsed.details)
      ? parsed.details.filter((d): d is string => typeof d === 'string')
      : undefined
    throw new ApiError(
      response.status,
      response.statusText,
      typeof parsed.code === 'string' ? parsed.code : undefined,
      undefined,
      details,
    )
  }

  return { blob: await response.blob(), filename: filenameOf(response.headers.get('Content-Disposition')) }
}

/** Hands the file to the browser as a download; the object URL is released after a while. */
export function saveBlob({ blob, filename }: BackupFile): void {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  document.body.appendChild(link)
  link.click()
  link.remove()
  setTimeout(() => URL.revokeObjectURL(url), 30_000)
}

export function useCreateBackup() {
  return useMutation({
    mutationFn: (passphrase: string) => requestBackup(passphrase),
    onSuccess: (file) => saveBlob(file),
  })
}
