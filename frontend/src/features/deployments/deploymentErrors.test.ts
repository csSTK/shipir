import { describe, expect, it } from 'vitest'
import { ApiError } from '../../shared/api/client.ts'
import '../../shared/i18n/index.ts'
import { describeDeploymentError } from './deploymentErrors.ts'

describe('describeDeploymentError', () => {
  it('explains the remote bind mount denial (shipr-kqbc.61) instead of the generic 403 text', () => {
    const error = new ApiError(403, 'x', 'REMOTE_BIND_MOUNT_DENIED')
    const text = describeDeploymentError(error, 'fallback')
    expect(text).toMatch(/bind mount/i)
    expect(text).toMatch(/named volume/i)
  })

  it('says that a stored compose or env file is too large, not the generic error', () => {
    const error = new ApiError(413, 'x', 'FILE_TOO_LARGE')
    expect(describeDeploymentError(error, 'fallback')).toMatch(/too large/i)
  })

  it('lists the other violations of a mixed refusal', () => {
    const error = new ApiError(403, 'x', 'REMOTE_BIND_MOUNT_DENIED', undefined, ['x: privileged'])
    expect(describeDeploymentError(error, 'fallback')).toMatch(/named volume.*x: privileged/i)
  })
})
