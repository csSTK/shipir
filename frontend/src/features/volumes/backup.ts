import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ApiError, apiFetch, readCookie } from '../../shared/api/client.ts'
import { saveBlob } from '../settings/api.ts'

export const MAX_RESTORE_BYTES = 100 * 1024 * 1024
export const MAX_RESTORE_LABEL = '100 MB'

export type BackupStatus = 'SUCCESS' | 'FAILED'

/** One schedule of `GET /api/hosts/{id}/volume-backup-schedules`. */
export interface BackupSchedule {
  id: string
  volumeName: string
  intervalHours: number
  keepCount: number
  nextRunAt: string | null
  lastRunAt: string | null
  lastStatus: BackupStatus | null
}

export interface ScheduleRequest {
  volumeName: string
  intervalHours: number
  keepCount: number
}

const volumePath = (hostId: string, volume: string) => `/api/hosts/${hostId}/volumes/${encodeURIComponent(volume)}`
const schedulesPath = (hostId: string) => `/hosts/${hostId}/volume-backup-schedules`

async function toApiError(response: Response): Promise<ApiError> {
  const text = await response.text()
  let code: string | undefined
  try {
    const parsed: unknown = JSON.parse(text)
    if (parsed && typeof parsed === 'object' && 'code' in parsed && typeof parsed.code === 'string') code = parsed.code
  } catch {
    // not JSON: keep the generic error
  }
  return new ApiError(response.status, response.statusText, code)
}

function filenameOf(disposition: string | null, fallback: string): string {
  return disposition?.match(/filename="?([^";]+)"?/i)?.[1] ?? fallback
}

/** GET the tar and hand it to the browser. Not `apiFetch`: the answer is a file, not JSON. */
export async function downloadVolumeBackup(hostId: string, volume: string): Promise<void> {
  const response = await fetch(`${volumePath(hostId, volume)}/backup`, {
    credentials: 'same-origin',
    headers: { Accept: 'application/x-tar, application/json' },
  })
  if (!response.ok) throw await toApiError(response)
  saveBlob({ blob: await response.blob(), filename: filenameOf(response.headers.get('Content-Disposition'), `${volume}.tar`) })
}

/** POST multipart `file` + `confirm`; the browser sets the multipart boundary itself. */
export async function restoreVolume(hostId: string, volume: string, file: File, confirm: boolean): Promise<void> {
  const form = new FormData()
  form.append('file', file)
  form.append('confirm', String(confirm))
  const csrf = readCookie('XSRF-TOKEN')
  const response = await fetch(`${volumePath(hostId, volume)}/restore`, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { Accept: 'application/json', ...(csrf && { 'X-XSRF-TOKEN': csrf }) },
    body: form,
  })
  if (!response.ok) throw await toApiError(response)
}

const ERRORS: Record<string, string> = {
  RESOURCE_NOT_FOUND: 'This volume no longer exists.',
  HOST_NOT_FOUND: 'This environment does not exist or was deleted.',
  VOLUME_IN_USE: 'This volume is in use. Confirm below to restore into it anyway.',
  INVALID_ARCHIVE: 'The file is not a valid backup archive (a tar without unsafe paths or links).',
  SIZE_LIMIT_EXCEEDED: `The archive is larger than ${MAX_RESTORE_LABEL}.`,
  SCHEDULE_EXISTS: 'This volume already has a backup schedule.',
  INVALID_SCHEDULE: 'The schedule is not valid.',
  SCHEDULE_NOT_FOUND: 'This schedule no longer exists.',
  FORBIDDEN: 'You are not allowed to do this.',
  UNREACHABLE: 'Could not reach the Docker host.',
  TIMEOUT: 'The connection to the Docker host timed out.',
}

export function describeBackupError(error: unknown): string {
  if (error instanceof ApiError && error.code && ERRORS[error.code]) return ERRORS[error.code]
  return 'The request failed. Try again.'
}

export function useBackupSchedules(hostId: string, enabled = true) {
  return useQuery({
    queryKey: ['hosts', hostId, 'volume-backup-schedules'],
    queryFn: () => apiFetch<BackupSchedule[]>(schedulesPath(hostId)),
    enabled,
  })
}

export function useCreateSchedule(hostId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: ScheduleRequest) => apiFetch<BackupSchedule>(schedulesPath(hostId), { method: 'POST', body }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['hosts', hostId, 'volume-backup-schedules'] }),
  })
}

export function useDeleteSchedule(hostId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: string) => apiFetch<void>(`${schedulesPath(hostId)}/${id}`, { method: 'DELETE' }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['hosts', hostId, 'volume-backup-schedules'] }),
  })
}
