import { ApiError } from '../../shared/api/client.ts'

/** Maps stack API failures to short user-facing messages. */
export function describeStackError(error: unknown, fallback: string): string {
  if (error instanceof ApiError) {
    switch (error.code) {
      case 'DEPLOYMENT_IN_PROGRESS':
        return 'A deployment is already running for this stack. Wait until it finishes.'
      case 'STACK_NAME_TAKEN':
        return 'A stack with this name already exists in this environment.'
      case 'ENCRYPTION_KEY_MISSING':
        return 'The server has no encryption key configured, so environment variables cannot be stored.'
      case 'INVALID_STACK_NAME':
        return 'Use lowercase letters, digits, hyphens and underscores, starting with a letter or digit.'
      case 'INVALID_COMPOSE':
        return ['The Compose file is not valid.', ...(error.details ?? [])].join(' ')
      case 'INVALID_ENV':
        return 'An environment variable is invalid. Check names and values.'
      case 'GIT_MANAGED':
        return 'The Compose file of a Git stack comes from the repository and cannot be edited here.'
      case 'STACK_NOT_FOUND':
        return 'This stack no longer exists.'
      case 'VERSION_NOT_FOUND':
        return 'This version no longer exists.'
      case 'COMPOSE_DOWN_FAILED':
        return 'Docker could not stop the stack, so it was not deleted. Try again.'
      case 'HOST_NOT_FOUND':
        return 'This environment no longer exists.'
      default:
        if (error.status === 403) return 'You are not allowed to do this.'
    }
  }
  return fallback
}
