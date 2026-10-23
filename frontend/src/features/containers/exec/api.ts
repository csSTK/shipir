import { useMutation } from '@tanstack/react-query'
import { ApiError, apiFetch } from '../../../shared/api/client.ts'

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
  INVALID_CONTAINER_STATE: 'The container is not running. Start it to open a console.',
  EXEC_LIMIT_REACHED: 'Too many consoles are open. Close one and try again.',
  RESOURCE_NOT_FOUND: 'This container no longer exists.',
  HOST_NOT_FOUND: 'This environment does not exist or was deleted.',
  INVALID_SHELL: 'This shell is not allowed.',
  INVALID_SIZE: 'The terminal size is not valid.',
  UNREACHABLE: 'Could not reach the Docker host.',
  TIMEOUT: 'The connection to the Docker host timed out.',
}

export function describeExecError(error: unknown): string {
  const code = error instanceof ApiError ? error.code : null
  return (code && ERROR_TEXT[code]) || 'Could not open the console.'
}
