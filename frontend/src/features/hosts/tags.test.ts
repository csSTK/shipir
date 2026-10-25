import { describe, expect, it } from 'vitest'
import { ApiError } from '../../shared/api/client.ts'
import { addTag, describeTagError, envTags, normalizeTag } from './tags.ts'

describe('normalizeTag', () => {
  it('trims and lowercases', () => {
    expect(normalizeTag('  Prod-EU ')).toBe('prod-eu')
  })
})

describe('addTag', () => {
  it('adds a normalised tag', () => {
    expect(addTag(['eu'], '  Prod ')).toEqual({ tags: ['eu', 'prod'], problem: null })
  })

  it('ignores an empty draft without a message', () => {
    expect(addTag(['eu'], '   ')).toEqual({ tags: ['eu'], problem: null })
  })

  it.each(['-prod', 'pro d', 'prod!', 'a'.repeat(25)])('rejects the invalid tag %s and keeps the list', (raw) => {
    const result = addTag(['eu'], raw)
    expect(result.tags).toEqual(['eu'])
    expect(result.problem).toMatch(/lowercase letters, digits or hyphens/i)
  })

  it('accepts a 24 character tag at the length limit', () => {
    const tag = 'a'.repeat(24)
    expect(addTag([], tag).tags).toEqual([tag])
  })

  it('reports a duplicate instead of adding it twice', () => {
    expect(addTag(['prod'], 'PROD')).toEqual({ tags: ['prod'], problem: expect.stringMatching(/already added/i) })
  })

  it('refuses an 11th tag', () => {
    const ten = Array.from({ length: 10 }, (_, i) => `t${i}`)
    expect(addTag(ten, 'extra')).toEqual({ tags: ten, problem: expect.stringMatching(/at most 10/i) })
  })
})

describe('describeTagError', () => {
  it.each([
    ['TOO_MANY_TAGS', /at most 10/i],
    ['INVALID_TAG', /lowercase letters, digits or hyphens/i],
    ['TAGS_CONFLICT', /changed meanwhile|try again/i],
  ])('maps %s to a message', (code, text) => {
    expect(describeTagError(new ApiError(400, '', code))).toMatch(text)
  })

  it('falls back to a generic message', () => {
    expect(describeTagError(new Error('boom'))).toMatch(/could not save the tags/i)
  })
})

describe('envTags', () => {
  it('treats a missing tags field as no tags', () => {
    expect(envTags({})).toEqual([])
  })

  it('returns the tags when present', () => {
    expect(envTags({ tags: ['prod'] })).toEqual(['prod'])
  })
})
