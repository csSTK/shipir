import { ApiError } from '../../shared/api/client.ts'
import i18n from 'i18next'

/** Maps deployment API failures to short user-facing messages. */
export function describeDeploymentError(error: unknown, fallback: string): string {
  if (error instanceof ApiError) {
    switch (error.code) {
      case 'DEPLOYMENT_IN_PROGRESS':
        return i18n.t('deployments:deploymentErrors.aRunIsAlreadyIn')
      case 'FILES_CHANGED_SINCE_REVIEW':
        return i18n.t('deployments:deploymentErrors.filesChangedSinceReview')
      case 'REVIEW_REQUIRED':
        return i18n.t('deployments:deploymentErrors.reviewRequired')
      case 'DEPLOYMENT_NAME_TAKEN':
        return i18n.t('deployments:deploymentErrors.aDeploymentWithThisName')
      case 'ENCRYPTION_KEY_MISSING':
        return i18n.t('deployments:deploymentErrors.theServerHasNoEncryption')
      case 'INVALID_DEPLOYMENT_NAME':
        return i18n.t('deployments:deploymentErrors.useLowercaseLettersDigitsHyphens')
      case 'INVALID_COMPOSE':
        return [i18n.t('deployments:deploymentErrors.theComposeFileIsNot'), ...(error.details ?? [])].join(' ')
      case 'ENV_VALUES_RESTRICTED':
        return i18n.t('deployments:deploymentErrors.variableValuesNeedOperateAccess')
      case 'INVALID_ENV':
        return i18n.t('deployments:deploymentErrors.anEnvironmentVariableIsInvalid')
      case 'GIT_MANAGED':
        return i18n.t('deployments:deploymentErrors.theComposeFileOfA')
      case 'GIT_AUTH_FAILED':
        return i18n.t('deployments:deploymentErrors.theRepositoryRejectedTheCredentials')
      case 'GIT_REF_NOT_FOUND':
        return i18n.t('deployments:deploymentErrors.theBranchOrTagWas')
      case 'GIT_REPO_NOT_FOUND':
        return i18n.t('deployments:deploymentErrors.theRepositoryWasNotFound')
      case 'GIT_UNREACHABLE':
        return i18n.t('deployments:deploymentErrors.theGitServerCouldNot')
      case 'COMPOSE_FILE_NOT_FOUND':
        return i18n.t('deployments:deploymentErrors.theRepositoryHasNoCompose')
      case 'INVALID_COMPOSE_PATH':
        return i18n.t('deployments:deploymentErrors.theComposeFilePathMust')
      case 'INVALID_REPO_URL':
        return i18n.t('deployments:deploymentErrors.enterAValidHttpsRepository')
      case 'CREDENTIAL_NOT_FOUND':
        return i18n.t('deployments:deploymentErrors.thisCredentialNoLongerExists')
      case 'NOT_A_GIT_DEPLOYMENT':
        return i18n.t('deployments:deploymentErrors.thisDeploymentIsNotSourced')
      case 'NOT_A_FILE_DEPLOYMENT':
        return i18n.t('deployments:deploymentErrors.automaticImageUpdatesAreOnly')
      case 'NOT_DEPLOYED':
        return i18n.t('deployments:deploymentErrors.deployThisDeploymentOnceBefore')
      case 'DEPLOYMENT_NOT_FOUND':
        return i18n.t('deployments:deploymentErrors.thisDeploymentNoLongerExists')
      case 'VERSION_NOT_FOUND':
        return i18n.t('deployments:deploymentErrors.thisVersionNoLongerExists')
      case 'COMPOSE_DOWN_FAILED':
        return i18n.t('deployments:deploymentErrors.dockerCouldNotStopThe')
      case 'MANAGED_DEPLOYMENT':
        return i18n.t('deployments:deploymentErrors.thisDeploymentIsManagedBy')
      case 'PROJECT_NOT_FOUND':
        return i18n.t('deployments:deploymentErrors.thisProjectNoLongerExists')
      case 'PATH_NOT_ALLOWED':
        return i18n.t('deployments:deploymentErrors.theComposeFileOfThis')
      case 'RELATIVE_PATHS_NOT_SUPPORTED':
        return [
          i18n.t('deployments:deploymentErrors.thisProjectUsesPathsRelative'),
          ...(error.details ?? []),
        ].join(' ')
      case 'HOST_NOT_FOUND':
        return i18n.t('deployments:deploymentErrors.thisEnvironmentNoLongerExists')
      default:
        if (error.status === 403) return i18n.t('deployments:deploymentErrors.youAreNotAllowedTo')
    }
  }
  return fallback
}

const POLL_ERRORS: Record<string, string> = {
  get GIT_AUTH_FAILED() { return i18n.t('deployments:deploymentErrors.authenticationFailed') },
  get GIT_REF_NOT_FOUND() { return i18n.t('deployments:deploymentErrors.branchOrTagNotFound') },
  get GIT_REPO_NOT_FOUND() { return i18n.t('deployments:deploymentErrors.repositoryNotFound') },
  get GIT_UNREACHABLE() { return i18n.t('deployments:deploymentErrors.gitServerUnreachable') },
  get COMPOSE_FILE_NOT_FOUND() { return i18n.t('deployments:deploymentErrors.composeFileNotFound') },
}

/** Short text for the `lastPollError` code of a Git deployment's auto-update check. */
export function describePollError(code: string): string {
  return POLL_ERRORS[code] ?? i18n.t('deployments:deploymentErrors.theLastCheckFailed')
}
