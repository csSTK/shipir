import type { TailOption } from './logStream.ts'

/** Download URL for one container. */
export function logDownloadHref(hostId: string, containerId: string, tail: TailOption, timestamps: boolean): string {
  return `/api/hosts/${hostId}/containers/${containerId}/logs/download?tail=${tail}&timestamps=${timestamps}`
}

/** The download endpoint answered with an error; `code` is the server's error code, if the body had one. */
export class LogDownloadError extends Error {
  readonly status: number
  readonly code?: string
  constructor(status: number, code?: string) {
    super(code ?? `HTTP ${status}`)
    this.status = status
    this.code = code
  }
}

const FALLBACK_NAME = 'container-logs.txt'

/** Basename only, without path separators, ':' or control characters; the fallback when nothing usable is left. */
function safeFilename(raw: string | undefined): string {
  // eslint-disable-next-line no-control-regex
  const name = (raw ?? '').split(/[/\\]/).pop()!.replace(/[:\u0000-\u001f\u007f-\u009f]/g, '').trim()
  return name === '' || /^\.+$/.test(name) ? FALLBACK_NAME : name
}

/**
 * Fetches the log file and hands it to the browser. Not a plain anchor, so a refusal such as
 * 429 TOO_MANY_DOWNLOADS can be shown inline instead of a broken download.
 */
export async function downloadLog(href: string): Promise<void> {
  const response = await fetch(href, { credentials: 'same-origin' })
  if (!response.ok) {
    let code: string | undefined
    try {
      const body = (await response.json()) as { code?: unknown }
      if (typeof body.code === 'string') code = body.code
    } catch {
      // not JSON: status alone
    }
    throw new LogDownloadError(response.status, code)
  }
  const filename = safeFilename(response.headers.get('Content-Disposition')?.match(/filename="?([^";]+)"?/i)?.[1])
  const url = URL.createObjectURL(await response.blob())
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  document.body.appendChild(link)
  link.click()
  link.remove()
  setTimeout(() => URL.revokeObjectURL(url), 30_000)
}
