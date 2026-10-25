import type { TailOption } from './logStream.ts'

/** Download URL for one container; the browser streams it to disk. */
export function logDownloadHref(hostId: string, containerId: string, tail: TailOption, timestamps: boolean): string {
  return `/api/hosts/${hostId}/containers/${containerId}/logs/download?tail=${tail}&timestamps=${timestamps}`
}
