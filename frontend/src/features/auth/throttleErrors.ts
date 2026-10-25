import { ApiError } from '../../shared/api/client.ts'

/** Text for a throttled (429) auth request, naming the wait from `Retry-After`; `null` for any other error. */
export function throttleMessage(error: unknown): string | null {
  if (!(error instanceof ApiError) || error.status !== 429) return null
  const seconds = error.retryAfterSeconds
  if (seconds === undefined) return 'Too many attempts. Try again later.'
  return `Too many attempts, try again in ${seconds} ${seconds === 1 ? 'second' : 'seconds'}.`
}
