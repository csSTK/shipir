const API_BASE = '/api'
const CSRF_COOKIE = 'XSRF-TOKEN'
const CSRF_HEADER = 'X-XSRF-TOKEN'

export class ApiError extends Error {
  readonly status: number
  /** Machine-readable error code from the response body (e.g. `SETUP_REQUIRED`), if any. */
  readonly code?: string
  /** Rule violation codes from the response body (e.g. `PASSWORD_POLICY`), if any. */
  readonly violations?: string[]

  constructor(status: number, message: string, code?: string, violations?: string[]) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.violations = violations
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

function parseErrorBody(text: string): { code?: string; violations?: string[] } {
  try {
    const parsed: unknown = JSON.parse(text)
    if (!parsed || typeof parsed !== 'object') return {}
    const code = 'code' in parsed && typeof parsed.code === 'string' ? parsed.code : undefined
    const violations =
      'violations' in parsed &&
      Array.isArray(parsed.violations) &&
      parsed.violations.every((v): v is string => typeof v === 'string')
        ? parsed.violations
        : undefined
    return { code, violations }
  } catch {
    return {} // body is not JSON
  }
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
    const { code, violations } = parseErrorBody(text)
    throw new ApiError(response.status, text || response.statusText, code, violations)
  }
  if (response.status === 204) {
    return undefined as T
  }
  return (await response.json()) as T
}
