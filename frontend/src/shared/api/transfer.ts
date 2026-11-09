import { ApiError, CSRF_COOKIE, CSRF_HEADER, parseErrorBody, readCookie, retryAfterSeconds } from './client.ts'

/** A file received from the backend: the bytes and the name the server suggested, if any. */
export interface DownloadedFile {
  blob: Blob
  /** Raw `Content-Disposition` filename, not sanitised: callers pick a fallback and sanitise as needed. */
  filename: string | undefined
}

export function filenameFromDisposition(disposition: string | null): string | undefined {
  return disposition?.match(/filename="?([^";]+)"?/i)?.[1]
}

/** Hands a file to the browser as a download; the object URL is released after 30 seconds. */
export function saveBlob({ blob, filename }: { blob: Blob; filename: string }): void {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  document.body.appendChild(link)
  link.click()
  link.remove()
  setTimeout(() => URL.revokeObjectURL(url), 30_000)
}

interface DownloadOptions {
  method?: 'GET' | 'POST'
  body?: unknown
  accept?: string
  signal?: AbortSignal
}

/**
 * Fetches a file. Not `apiFetch`: the answer is bytes, not JSON. `url` is the full same-origin URL
 * (including `/api`). Non-2xx answers throw an `ApiError` with status, code, details and Retry-After;
 * the raw body is never kept in the message.
 */
export async function downloadFile(url: string, options: DownloadOptions = {}): Promise<DownloadedFile> {
  const { method = 'GET', body, accept = '*/*', signal } = options
  const csrf = method === 'GET' ? undefined : readCookie(CSRF_COOKIE)
  const response = await fetch(url, {
    method,
    signal,
    credentials: 'same-origin',
    headers: {
      Accept: accept,
      ...(body !== undefined && { 'Content-Type': 'application/json' }),
      ...(csrf && { [CSRF_HEADER]: csrf }),
    },
    body: body !== undefined ? JSON.stringify(body) : undefined,
  })
  if (!response.ok) throw await errorOf(response)
  return { blob: await response.blob(), filename: filenameFromDisposition(response.headers.get('Content-Disposition')) }
}

async function errorOf(response: Response): Promise<ApiError> {
  const text = await response.text().catch(() => '') // an unreadable body still yields status-only error
  const { code, violations, details } = parseErrorBody(text)
  return new ApiError(response.status, response.statusText, code, violations, details, retryAfterSeconds(response))
}

async function sendForm(url: string, form: FormData): Promise<Response> {
  const csrf = readCookie(CSRF_COOKIE)
  const response = await fetch(url, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { Accept: 'application/json', ...(csrf && { [CSRF_HEADER]: csrf }) },
    body: form,
  })
  if (!response.ok) throw await errorOf(response)
  return response
}

/** POST a multipart form with fetch (no progress); the browser sets the boundary. Rejects with `ApiError`. */
export async function postForm(url: string, form: FormData): Promise<void> {
  await sendForm(url, form)
}

/** Like `postForm`, for endpoints that answer with a JSON body. */
export async function postFormJson<T>(url: string, form: FormData): Promise<T> {
  return (await (await sendForm(url, form)).json()) as T
}

interface UploadOptions {
  method?: 'POST' | 'PUT'
  onProgress?: (fraction: number) => void
  /** Texts for failures that have no server answer; the caller owns the (translated) wording. */
  messages: { network: string; aborted: string }
}

/**
 * Multipart upload over XMLHttpRequest, because fetch has no upload progress. Resolves on 2xx,
 * rejects with `ApiError` (status 0 for network errors, code `ABORTED` after `abort()`).
 */
export function uploadMultipart(
  url: string,
  form: FormData,
  { method = 'POST', onProgress, messages }: UploadOptions,
): { promise: Promise<void>; abort: () => void } {
  const xhr = new XMLHttpRequest()
  const promise = new Promise<void>((resolve, reject) => {
    xhr.open(method, url)
    const csrf = readCookie(CSRF_COOKIE)
    if (csrf) xhr.setRequestHeader(CSRF_HEADER, csrf)
    xhr.setRequestHeader('Accept', 'application/json')
    xhr.upload.onprogress = (e) => {
      if (e.lengthComputable) onProgress?.(e.loaded / e.total)
    }
    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300) return resolve()
      const { code, violations, details } = parseErrorBody(xhr.responseText)
      reject(new ApiError(xhr.status, xhr.responseText || xhr.statusText, code, violations, details))
    }
    xhr.onerror = () => reject(new ApiError(0, messages.network))
    xhr.onabort = () => reject(new ApiError(0, messages.aborted, 'ABORTED'))
    xhr.send(form)
  })
  return { promise, abort: () => xhr.abort() }
}
