import { afterEach, describe, expect, it } from 'vitest'
import { ApiError } from '../../shared/api/client.ts'
import { i18n } from '../../shared/i18n/index.ts'
import { describeDeleteError } from './deleteResource.ts'

describe('describeDeleteError', () => {
  afterEach(() => i18n.changeLanguage('en'))

  it('translates RESOURCE_IN_USE instead of showing the server text', async () => {
    await i18n.changeLanguage('de')
    const error = new ApiError(409, JSON.stringify({ message: 'Resource is in use' }), 'RESOURCE_IN_USE')
    expect(describeDeleteError(error)).toBe('Diese Ressource wird verwendet oder ist geschützt und kann nicht gelöscht werden.')
  })

  it('translates PROXY_IMAGE_UNRESOLVED (shipr-kqbc.63)', async () => {
    const error = new ApiError(409, JSON.stringify({ message: 'x' }), 'PROXY_IMAGE_UNRESOLVED')
    expect(describeDeleteError(error)).toContain('proxy')
  })

  it('falls back to a translated generic text for unknown errors', async () => {
    await i18n.changeLanguage('de')
    expect(describeDeleteError(new ApiError(500, 'raw english body'))).not.toContain('raw english')
  })
})
