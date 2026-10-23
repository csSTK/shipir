const API_BASE = '/api'
const CSRF_COOKIE = 'XSRF-TOKEN'
const CSRF_HEADER = 'X-XSRF-TOKEN'

export class ApiError extends Error {
  readonly status: number
  /** Machine-readable error code from the response body (e.g. `SETUP_REQUIRED`), if any. */
  readonly code?: string

  constructor(status: number, message: string, code?: string) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
  }
}

interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'
  body?: unknown
  signal?: AbortSignal
}

function readCookie(name: string): string | undefined {
  const prefix = `${name}=`
  const entry = document.cookie.split('; ').find((part) => part.startsWith(prefix))
  return entry ? decodeURIComponent(entry.slice(prefix.length)) : undefined
}

function parseErrorCode(text: string): string | undefined {
  try {
    const parsed: unknown = JSON.parse(text)
    if (parsed && typeof parsed === 'object' && 'code' in parsed && typeof parsed.code === 'string') {
      return parsed.code
    }
  } catch {
    // body is not JSON
  }
  return undefined
}

/** Typed JSON fetch wrapper for the backend REST API. `path` is relative to /api. */
export async function apiFetch<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { method = 'GET', body, signal } = options
  const csrfToken = method === 'GET' ? undefined : readCookie(CSRF_COOKIE)
  const response = await fetch(`${API_BASE}${path}`, {
    method,
    signal,
    credentials: 'same-origin',
    headers: {
      Accept: 'application/json',
      ...(body !== undefined && { 'Content-Type': 'application/json' }),
      ...(csrfToken && { [CSRF_HEADER]: csrfToken }),
    },
    body: body !== undefined ? JSON.stringify(body) : undefined,
  })

  if (!response.ok) {
    const text = await response.text()
    throw new ApiError(response.status, text || response.statusText, parseErrorCode(text))
  }
  if (response.status === 204) {
    return undefined as T
  }
  return (await response.json()) as T
}
