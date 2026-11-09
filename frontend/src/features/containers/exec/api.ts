import { useMutation, useQuery } from '@tanstack/react-query'
import { ApiError, apiFetch } from '../../../shared/api/client.ts'
import i18n from 'i18next'
import { useCurrentUser } from '../../auth/index.ts'
import { useContainers } from '../api.ts'

export type Shell = '/bin/sh' | '/bin/bash'

/** Response of `POST /api/hosts/{id}/containers/{cid}/exec`: the STOMP destinations of the new session. */
export interface ExecSession {
  sessionId: string
  output: string
  input: string
  resize: string
}

interface StartExecInput {
  shell: Shell
  cols: number
  rows: number
}

export function useStartExec(hostId: string, containerId: string) {
  return useMutation({
    mutationFn: (body: StartExecInput) =>
      apiFetch<ExecSession>(`/hosts/${hostId}/containers/${containerId}/exec`, { method: 'POST', body }),
  })
}

/** Response of `GET /api/exec/capabilities`. */
interface ExecCapabilities {
  operatorEnabled: boolean
}

/**
 * Whether to offer the console: always to ADMIN, to OPERATOR only while the server enables operator exec, never
 * to VIEWER. The server stays authoritative (per host/deployment permission), so a 403 is still handled on the page.
 */
export function useConsoleAccess(): { allowed: boolean; pending: boolean } {
  const { data: user } = useCurrentUser()
  const role = user?.role
  const capabilities = useQuery({
    queryKey: ['exec', 'capabilities'],
    queryFn: () => apiFetch<ExecCapabilities>('/exec/capabilities'),
    enabled: role === 'OPERATOR',
  })
  return {
    allowed: role === 'ADMIN' || (role === 'OPERATOR' && capabilities.data?.operatorEnabled === true),
    pending: role === 'OPERATOR' && capabilities.isPending,
  }
}

/** Response of `GET /api/exec/access?hostId=`: where the user holds OPERATE (UI hint, the server re-checks). */
interface ExecScope {
  hostOperable: boolean
  operableProjects: string[]
}

function useExecScope(hostId: string, enabled: boolean) {
  return useQuery({
    queryKey: ['exec', 'access', hostId],
    queryFn: () => apiFetch<ExecScope>(`/exec/access?hostId=${encodeURIComponent(hostId)}`),
    enabled: enabled && hostId !== '',
  })
}

/**
 * Per-container console visibility: ADMIN everywhere; an OPERATOR (while operator exec is on) only with OPERATE on
 * the host or on the managed deployment owning the container (its Compose project). Never VIEWER. Hides the action
 * only; the server stays authoritative and a 403 is still handled on the console page.
 */
export function useCanOpenConsole(hostId: string): (composeProject: string | null) => boolean {
  const { allowed } = useConsoleAccess()
  const { data: user } = useCurrentUser()
  const isOperator = user?.role === 'OPERATOR'
  const scope = useExecScope(hostId, allowed && isOperator)
  if (!allowed) return () => false
  if (!isOperator) return () => true
  const data = scope.data
  return (composeProject) =>
    !!data && (data.hostOperable || (composeProject !== null && data.operableProjects.includes(composeProject)))
}

/**
 * Console route gate for one container: like {@link useCanOpenConsole}, but pending until the OPERATOR's scope (and,
 * when only deployments are operable, the container's Compose project) is known. If the hint cannot be loaded the
 * page opens anyway, because the server re-checks on Connect.
 */
export function useConsoleRouteAccess(hostId: string, containerId: string): { allowed: boolean; pending: boolean } {
  const base = useConsoleAccess()
  const { data: user } = useCurrentUser()
  const needsScope = base.allowed && user?.role === 'OPERATOR'
  const scope = useExecScope(hostId, needsScope)
  const needsProject = needsScope && !!scope.data && !scope.data.hostOperable && scope.data.operableProjects.length > 0
  const containers = useContainers(hostId, needsProject)
  if (!needsScope) return base
  if (scope.isPending) return { allowed: false, pending: true }
  if (!scope.data) return { allowed: true, pending: false }
  if (scope.data.hostOperable) return { allowed: true, pending: false }
  if (!needsProject) return { allowed: false, pending: false }
  if (containers.isPending) return { allowed: false, pending: true }
  const project = containers.data?.find((c) => c.id === containerId)?.composeProject
  return { allowed: !!project && scope.data.operableProjects.includes(project), pending: false }
}

const ERROR_TEXT: Record<string, string> = {
  get FORBIDDEN() { return i18n.t('containers:api.youDontHavePermissionToOpen') },
  get INVALID_CONTAINER_STATE() { return i18n.t('containers:api.theContainerIsNotRunning') },
  get EXEC_LIMIT_REACHED() { return i18n.t('containers:api.tooManyConsolesAreOpen') },
  get RESOURCE_NOT_FOUND() { return i18n.t('containers:api.thisContainerNoLongerExists') },
  get HOST_NOT_FOUND() { return i18n.t('containers:api.thisEnvironmentDoesNotExist') },
  get INVALID_SHELL() { return i18n.t('containers:api.thisShellIsNotAllowed') },
  get INVALID_SIZE() { return i18n.t('containers:api.theTerminalSizeIsNot') },
  get UNREACHABLE() { return i18n.t('containers:api.couldNotReachTheDocker') },
  get TIMEOUT() { return i18n.t('containers:api.theConnectionToTheDocker') },
}

export function describeExecError(error: unknown): string {
  const code = error instanceof ApiError ? error.code : null
  return (code && ERROR_TEXT[code]) || i18n.t('containers:api.couldNotOpenTheConsole')
}
