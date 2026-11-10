const API_BASE = "/api";
export const CSRF_COOKIE = "XSRF-TOKEN";
export const CSRF_HEADER = "X-XSRF-TOKEN";

export class ApiError extends Error {
  readonly status: number;
  /** Machine-readable error code from the response body (e.g. `SETUP_REQUIRED`), if any. */
  readonly code?: string;
  /** Rule violation codes from the response body (e.g. `PASSWORD_POLICY`), if any. */
  readonly violations?: string[];
  /** Human-readable detail lines from the response body (e.g. `port: must be ...`), if any. */
  readonly details?: string[];
  /** Seconds from a numeric `Retry-After` response header (set on 429 throttling), if any. */
  readonly retryAfterSeconds?: number;
  /** The `message` field of a JSON response body, if there is a non-blank one (message itself is the raw body). */
  readonly serverMessage?: string;
  /** The request path relative to /api (set by `apiFetch`), if known. */
  readonly path?: string;

  constructor(
    status: number,
    message: string,
    code?: string,
    violations?: string[],
    details?: string[],
    retryAfterSeconds?: number,
    serverMessage?: string,
    path?: string,
  ) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.code = code;
    this.violations = violations;
    this.details = details;
    this.retryAfterSeconds = retryAfterSeconds;
    this.serverMessage = serverMessage;
    this.path = path;
  }
}

interface RequestOptions {
  method?: "GET" | "POST" | "PUT" | "PATCH" | "DELETE";
  body?: unknown;
  signal?: AbortSignal;
}

export function readCookie(name: string): string | undefined {
  const prefix = `${name}=`;
  const entry = document.cookie
    .split("; ")
    .find((part) => part.startsWith(prefix));
  return entry ? decodeURIComponent(entry.slice(prefix.length)) : undefined;
}

function stringArray(value: unknown): string[] | undefined {
  return Array.isArray(value) &&
    value.every((v): v is string => typeof v === "string")
    ? value
    : undefined;
}

/** Only a plain number of seconds counts; an HTTP date or junk is ignored. */
export function retryAfterSeconds(response: Response): number | undefined {
  const value = response.headers.get("Retry-After");
  return value !== null && /^\d+$/.test(value.trim())
    ? Number(value)
    : undefined;
}

export function parseErrorBody(text: string): {
  code?: string;
  violations?: string[];
  details?: string[];
  serverMessage?: string;
} {
  try {
    const parsed: unknown = JSON.parse(text);
    if (!parsed || typeof parsed !== "object") return {};
    const code =
      "code" in parsed && typeof parsed.code === "string"
        ? parsed.code
        : undefined;
    const violations =
      "violations" in parsed ? stringArray(parsed.violations) : undefined;
    const details =
      "details" in parsed ? stringArray(parsed.details) : undefined;
    const serverMessage =
      "message" in parsed &&
      typeof parsed.message === "string" &&
      parsed.message.trim()
        ? parsed.message
        : undefined;
    return { code, violations, details, serverMessage };
  } catch {
    return {}; // body is not JSON
  }
}

/** The request path relative to /api, as `ApiError.path` carries it; other URLs are returned unchanged. */
export function pathRelativeToApi(url: string): string {
  return url.startsWith(`${API_BASE}/`) ? url.slice(API_BASE.length) : url;
}

/** Builds the `ApiError` for a failed answer from its raw body text; shared by every client that talks to the API. */
export function apiErrorFromBody(
  status: number,
  message: string,
  bodyText: string,
  path: string,
  retryAfterSeconds?: number,
): ApiError {
  const { code, violations, details, serverMessage } = parseErrorBody(bodyText);
  return new ApiError(
    status,
    message,
    code,
    violations,
    details,
    retryAfterSeconds,
    serverMessage,
    path,
  );
}

/** Typed JSON fetch wrapper for the backend REST API. `path` is relative to /api. */
export async function apiFetch<T>(
  path: string,
  options: RequestOptions = {},
): Promise<T> {
  const { method = "GET", body, signal } = options;
  const csrfToken = method === "GET" ? undefined : readCookie(CSRF_COOKIE);
  const response = await fetch(`${API_BASE}${path}`, {
    method,
    signal,
    credentials: "same-origin",
    headers: {
      Accept: "application/json",
      ...(body !== undefined && { "Content-Type": "application/json" }),
      ...(csrfToken && { [CSRF_HEADER]: csrfToken }),
    },
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });

  if (!response.ok) {
    const text = await response.text();
    throw apiErrorFromBody(
      response.status,
      text || response.statusText,
      text,
      path,
      retryAfterSeconds(response),
    );
  }
  if (response.status === 204) {
    return undefined as T;
  }
  return (await response.json()) as T;
}
