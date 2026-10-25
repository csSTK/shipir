import { describe, expect, it } from 'vitest'
import { hasContextExtension, isValidBuildArgKey, isValidReference, isValidRepository, isValidTag } from './buildRules.ts'

describe('build rules mirror the server grammar', () => {
  it('accepts tag references and rejects digests, uppercase paths and flags', () => {
    expect(isValidReference('myapp:1.0')).toBe(true)
    expect(isValidReference('ghcr.io/org/app:latest')).toBe(true)
    expect(isValidReference('localhost:5000/team/app')).toBe(true)
    expect(isValidReference('Bad Ref')).toBe(false)
    expect(isValidReference('app/UPPER:1')).toBe(false)
    expect(isValidReference('--rm')).toBe(false)
    expect(isValidReference(`app@sha256:${'a'.repeat(64)}`)).toBe(false)
    expect(isValidReference('')).toBe(false)
  })

  it('validates repository and tag separately', () => {
    expect(isValidRepository('registry.example.com/team/app')).toBe(true)
    expect(isValidRepository('app:1')).toBe(false)
    expect(isValidTag('v1.2-rc_1')).toBe(true)
    expect(isValidTag('-bad')).toBe(false)
    expect(isValidTag('a'.repeat(129))).toBe(false)
  })

  it('validates build arg keys and context file names', () => {
    expect(isValidBuildArgKey('NODE_ENV')).toBe(true)
    expect(isValidBuildArgKey('1BAD')).toBe(false)
    expect(isValidBuildArgKey('A-B')).toBe(false)
    expect(hasContextExtension('ctx.tar')).toBe(true)
    expect(hasContextExtension('ctx.TAR.GZ')).toBe(true)
    expect(hasContextExtension('ctx.tgz')).toBe(true)
    expect(hasContextExtension('ctx.zip')).toBe(false)
  })
})
