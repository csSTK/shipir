import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ApiError, apiFetch, readCookie } from '../../shared/api/client.ts'
import { saveBlob } from '../settings/api.ts'
import i18n from 'i18next'

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

/** One archive of `GET /api/hosts/{id}/volumes/{v}/stored-backups` (written by a scheduled backup). */
export interface StoredBackup {
  name: string
  sizeBytes: number
  createdAt: string
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

/** GET one stored archive by its exact name and hand it to the browser. */
export async function downloadStoredBackup(hostId: string, volume: string, fileName: string): Promise<void> {
  const response = await fetch(`${volumePath(hostId, volume)}/stored-backups/${encodeURIComponent(fileName)}`, {
    credentials: 'same-origin',
    headers: { Accept: 'application/x-tar, application/json' },
  })
  if (!response.ok) throw await toApiError(response)
  saveBlob({ blob: await response.blob(), filename: filenameOf(response.headers.get('Content-Disposition'), fileName) })
}

/** POST: restore one stored archive through the same pipeline as an upload; a volume in use needs `confirm`. */
export function restoreStoredBackup(hostId: string, volume: string, fileName: string, confirm: boolean): Promise<void> {
  return apiFetch<void>(
    `/hosts/${hostId}/volumes/${encodeURIComponent(volume)}/stored-backups/${encodeURIComponent(fileName)}/restore?confirm=${confirm}`,
    { method: 'POST' },
  )
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
  get RESOURCE_NOT_FOUND() { return i18n.t('volumes:backup.thisVolumeNoLongerExists') },
  get HOST_NOT_FOUND() { return i18n.t('volumes:backup.thisEnvironmentDoesNotExist') },
  get VOLUME_IN_USE() { return i18n.t('volumes:backup.thisVolumeIsInUse') },
  get INVALID_ARCHIVE() { return i18n.t('volumes:backup.theFileIsNotA') },
  get SIZE_LIMIT_EXCEEDED() { return i18n.t('volumes:backup.theArchiveIsLargerThan', { MAX_RESTORE_LABEL }) },
  get STORED_BACKUP_NOT_FOUND() { return i18n.t('volumes:backup.thisStoredBackupNoLongerExists') },
  get SCHEDULE_EXISTS() { return i18n.t('volumes:backup.thisVolumeAlreadyHasA') },
  get INVALID_SCHEDULE() { return i18n.t('volumes:backup.theScheduleIsNotValid') },
  get SCHEDULE_NOT_FOUND() { return i18n.t('volumes:backup.thisScheduleNoLongerExists') },
  get FORBIDDEN() { return i18n.t('volumes:backup.youAreNotAllowedTo') },
  get UNREACHABLE() { return i18n.t('volumes:backup.couldNotReachTheDocker') },
  get TIMEOUT() { return i18n.t('volumes:backup.theConnectionToTheDocker') },
}

export function describeBackupError(error: unknown): string {
  if (error instanceof ApiError && error.code && ERRORS[error.code]) return ERRORS[error.code]
  return i18n.t('volumes:backup.theRequestFailedTryAgain')
}

export function useBackupSchedules(hostId: string, enabled = true) {
  return useQuery({
    queryKey: ['hosts', hostId, 'volume-backup-schedules'],
    queryFn: () => apiFetch<BackupSchedule[]>(schedulesPath(hostId)),
    enabled,
  })
}

export function useStoredBackups(hostId: string, volume: string) {
  return useQuery({
    queryKey: ['hosts', hostId, 'volumes', volume, 'stored-backups'],
    queryFn: () => apiFetch<StoredBackup[]>(`/hosts/${hostId}/volumes/${encodeURIComponent(volume)}/stored-backups`),
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
    // deleting a schedule also deletes its stored archives, so every volume's stored list of this host is stale
    onSuccess: () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: ['hosts', hostId, 'volume-backup-schedules'] }),
        queryClient.invalidateQueries({ queryKey: ['hosts', hostId, 'volumes'] }),
      ]),
  })
}
