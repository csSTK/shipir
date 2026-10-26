import { useQuery } from '@tanstack/react-query'
import { ApiError, apiFetch, readCookie } from '../../../shared/api/client.ts'
import i18n from 'i18next'

export interface ContainerFileEntry {
  name: string
  path: string
  type: 'FILE' | 'DIRECTORY' | 'SYMLINK' | 'OTHER'
  size: number
  modified: string | null
  mode: string | null
  linkTarget: string | null
}

const base = (hostId: string, cid: string) => `/hosts/${hostId}/containers/${cid}/files`

export const filesKey = (hostId: string, cid: string, path: string) =>
  ['hosts', hostId, 'container-files', cid, path] as const

export function useContainerFiles(hostId: string, cid: string, path: string) {
  return useQuery({
    queryKey: filesKey(hostId, cid, path),
    queryFn: () => apiFetch<ContainerFileEntry[]>(`${base(hostId, cid)}?path=${encodeURIComponent(path)}`),
    retry: false,
  })
}

/** Same-origin URL the browser downloads directly (file, or `<name>.tar` for directories). */
export function downloadUrl(hostId: string, cid: string, path: string): string {
  return `/api${base(hostId, cid)}/download?path=${encodeURIComponent(path)}`
}

/** Upload with progress (fetch has no upload progress). Resolves on 204, rejects with `ApiError`. */
export function uploadFile(
  hostId: string,
  cid: string,
  dir: string,
  file: File,
  onProgress: (fraction: number) => void,
): { promise: Promise<void>; abort: () => void } {
  const xhr = new XMLHttpRequest()
  const promise = new Promise<void>((resolve, reject) => {
    xhr.open('PUT', `/api${base(hostId, cid)}?path=${encodeURIComponent(dir)}`)
    const token = readCookie('XSRF-TOKEN')
    if (token) xhr.setRequestHeader('X-XSRF-TOKEN', token)
    xhr.setRequestHeader('Accept', 'application/json')
    xhr.upload.onprogress = (e) => {
      if (e.lengthComputable) onProgress(e.loaded / e.total)
    }
    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300) return resolve()
      let code: string | undefined
      try {
        const parsed: unknown = JSON.parse(xhr.responseText)
        if (parsed && typeof parsed === 'object' && 'code' in parsed && typeof parsed.code === 'string') code = parsed.code
      } catch {
        // not JSON
      }
      reject(new ApiError(xhr.status, xhr.responseText || xhr.statusText, code))
    }
    xhr.onerror = () => reject(new ApiError(0, i18n.t('containers:api.networkError')))
    xhr.onabort = () => reject(new ApiError(0, i18n.t('containers:api.uploadCancelled'), 'ABORTED'))
    const form = new FormData()
    form.append('file', file)
    xhr.send(form)
  })
  return { promise, abort: () => xhr.abort() }
}

const LIST_ERRORS: Record<string, string> = {
  get DIRECTORY_TOO_LARGE() { return i18n.t('containers:api.thisDirectoryIsTooLarge') },
  get INVALID_PATH() { return i18n.t('containers:api.thisPathIsNotValid') },
  get RESOURCE_NOT_FOUND() { return i18n.t('containers:api.thisPathDoesNotExist') },
  get UNREACHABLE() { return i18n.t('containers:api.couldNotReachTheDocker') },
  get TIMEOUT() { return i18n.t('containers:api.theConnectionToTheDocker') },
}

const UPLOAD_ERRORS: Record<string, string> = {
  get SIZE_LIMIT_EXCEEDED() { return i18n.t('containers:api.theFileIsTooLarge') },
  get INVALID_FILE_NAME() { return i18n.t('containers:api.theFileNameIsNot') },
  get INVALID_UPLOAD() { return i18n.t('containers:api.theUploadWasRejected') },
  get INVALID_PATH() { return i18n.t('containers:api.thisPathIsNotValid') },
  get RESOURCE_NOT_FOUND() { return i18n.t('containers:api.theTargetDirectoryNoLonger') },
  get ABORTED() { return i18n.t('containers:api.uploadCancelled2') },
}

function describe(error: unknown, table: Record<string, string>, fallback: string): string {
  if (error instanceof ApiError) {
    if (error.code && table[error.code]) return table[error.code]
    if (error.status === 403) return i18n.t('containers:api.youAreNotAllowedTo')
  }
  return fallback
}

export const describeListError = (e: unknown) => describe(e, LIST_ERRORS, i18n.t('containers:api.couldNotLoadTheFiles'))
export const describeUploadError = (e: unknown) => describe(e, UPLOAD_ERRORS, i18n.t('containers:api.uploadFailed'))
