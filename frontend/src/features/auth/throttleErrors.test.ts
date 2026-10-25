import { describe, expect, it } from 'vitest'
import { ApiError } from '../../shared/api/client.ts'
import { throttleMessage } from './throttleErrors.ts'

describe('throttleMessage', () => {
  it('names the wait in seconds for a 429 with Retry-After', () => {
    expect(throttleMessage(new ApiError(429, 'x', 'TOO_MANY_ATTEMPTS', undefined, undefined, 42))).toBe(
      'Too many attempts, try again in 42 seconds.',
    )
  })

  it('uses the singular for one second', () => {
    expect(throttleMessage(new ApiError(429, 'x', 'TOO_MANY_ATTEMPTS', undefined, undefined, 1))).toBe(
      'Too many attempts, try again in 1 second.',
    )
  })

  it('falls back to a generic wait without Retry-After', () => {
    expect(throttleMessage(new ApiError(429, 'x', 'TOO_MANY_ATTEMPTS'))).toBe('Too many attempts. Try again later.')
  })

  it('returns null for other errors', () => {
    expect(throttleMessage(new ApiError(401, 'x'))).toBeNull()
    expect(throttleMessage(new Error('network'))).toBeNull()
    expect(throttleMessage(undefined)).toBeNull()
  })
})
