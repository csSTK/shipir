import { ApiError } from '../../shared/api/client.ts'

/** Maps deployment API failures to short user-facing messages. */
export function describeDeploymentError(error: unknown, fallback: string): string {
  if (error instanceof ApiError) {
    switch (error.code) {
      case 'DEPLOYMENT_IN_PROGRESS':
        return 'A run is already in progress for this deployment. Wait until it finishes.'
      case 'DEPLOYMENT_NAME_TAKEN':
        return 'A deployment with this name already exists in this environment.'
      case 'ENCRYPTION_KEY_MISSING':
        return 'The server has no encryption key configured, so environment variables cannot be stored.'
      case 'INVALID_DEPLOYMENT_NAME':
        return 'Use lowercase letters, digits, hyphens and underscores, starting with a letter or digit.'
      case 'INVALID_COMPOSE':
        return ['The Compose file is not valid.', ...(error.details ?? [])].join(' ')
      case 'INVALID_ENV':
        return 'An environment variable is invalid. Check names and values.'
      case 'GIT_MANAGED':
        return 'The Compose file of a Git deployment comes from the repository and cannot be edited here.'
      case 'GIT_AUTH_FAILED':
        return 'The repository rejected the credentials. Check the credential or choose another one.'
      case 'GIT_REF_NOT_FOUND':
        return 'The branch or tag was not found in the repository.'
      case 'GIT_REPO_NOT_FOUND':
        return 'The repository was not found. Check the URL, or select a credential for a private repository.'
      case 'GIT_UNREACHABLE':
        return 'The Git server could not be reached. Try again later.'
      case 'COMPOSE_FILE_NOT_FOUND':
        return 'The repository has no Compose file at this path.'
      case 'INVALID_COMPOSE_PATH':
        return 'The Compose file path must be relative to the repository root and stay inside it.'
      case 'INVALID_REPO_URL':
        return 'Enter a valid https:// repository URL.'
      case 'CREDENTIAL_NOT_FOUND':
        return 'This credential no longer exists. Choose another one.'
      case 'NOT_A_GIT_DEPLOYMENT':
        return 'This deployment is not sourced from a Git repository.'
      case 'DEPLOYMENT_NOT_FOUND':
        return 'This deployment no longer exists.'
      case 'VERSION_NOT_FOUND':
        return 'This version no longer exists.'
      case 'COMPOSE_DOWN_FAILED':
        return 'Docker could not stop the deployment, so it was not deleted. Try again.'
      case 'MANAGED_DEPLOYMENT':
        return 'This deployment is managed by Shipir; delete it from its detail page.'
      case 'HOST_NOT_FOUND':
        return 'This environment no longer exists.'
      default:
        if (error.status === 403) return 'You are not allowed to do this.'
    }
  }
  return fallback
}

const POLL_ERRORS: Record<string, string> = {
  GIT_AUTH_FAILED: 'Authentication failed',
  GIT_REF_NOT_FOUND: 'Branch or tag not found',
  GIT_REPO_NOT_FOUND: 'Repository not found',
  GIT_UNREACHABLE: 'Git server unreachable',
  COMPOSE_FILE_NOT_FOUND: 'Compose file not found',
}

/** Short text for the `lastPollError` code of a Git deployment's auto-update check. */
export function describePollError(code: string): string {
  return POLL_ERRORS[code] ?? 'The last check failed'
}
