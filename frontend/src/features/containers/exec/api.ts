import { useMutation } from '@tanstack/react-query'
import { ApiError, apiFetch } from '../../../shared/api/client.ts'
import i18n from 'i18next'

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

const ERROR_TEXT: Record<string, string> = {
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
