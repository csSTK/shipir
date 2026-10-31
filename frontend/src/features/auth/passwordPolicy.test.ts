import { describe, expect, it } from 'vitest'
import { checkPassword, isPasswordValid, violationMessage } from './passwordPolicy.ts'

const GOOD = 'Abcdefghijklm1!' // 15 chars, all classes

function failing(password: string, username = 'admin', confirmation = password) {
  return checkPassword(password, username, confirmation)
    .filter((rule) => !rule.met)
    .map((rule) => rule.id)
}

describe('passwordPolicy', () => {
  it('accepts a password satisfying every rule', () => {
    expect(failing(GOOD)).toEqual([])
    expect(isPasswordValid(GOOD, 'admin', GOOD)).toBe(true)
  })

  it('enforces the length bounds 15..128', () => {
    const pad = (n: number) => 'Aa1!' + 'x'.repeat(n - 4)
    expect(failing(pad(14))).toEqual(['length'])
    expect(failing(pad(15))).toEqual([])
    expect(failing(pad(128))).toEqual([])
    expect(failing(pad(129))).toEqual(['length'])
  })

  it('requires an uppercase letter', () => {
    expect(failing('abcdefghijklm1!')).toEqual(['uppercase'])
  })
  it('requires a lowercase letter', () => {
    expect(failing('ABCDEFGHIJKLM1!')).toEqual(['lowercase'])
  })
  it('requires a digit', () => {
    expect(failing('Abcdefghijklmn!')).toEqual(['digit'])
  })
  it('requires a symbol (anything not a letter or digit)', () => {
    expect(failing('Abcdefghijklmn1')).toEqual(['symbol'])
    expect(failing('Abcdefghijkl m1')).toEqual([]) // space counts as symbol
    expect(failing('Abcdefghijklmü1')).toEqual(['symbol']) // non-ASCII letter is not a symbol
  })

  it('rejects a password equal to the username, ignoring case', () => {
    const name = 'Abcdefghijklm1!'
    expect(failing(name, name)).toEqual(['notUsername'])
    expect(failing(name, name.toLowerCase())).toEqual(['notUsername'])
  })

  it('requires the confirmation to match', () => {
    expect(failing(GOOD, 'admin', GOOD + 'x')).toEqual(['match'])
    expect(isPasswordValid(GOOD, 'admin', '')).toBe(false)
  })

  it('maps every backend violation code to a readable message', () => {
    for (const code of [
      'TOO_SHORT',
      'TOO_LONG',
      'MISSING_UPPERCASE',
      'MISSING_LOWERCASE',
      'MISSING_DIGIT',
      'MISSING_SYMBOL',
      'EQUALS_USERNAME',
      'SAME_AS_CURRENT',
    ]) {
      expect(violationMessage(code)).not.toBe(code)
    }
    expect(violationMessage('SOMETHING_NEW')).toMatch(/password/i)
  })

  it('shows the fallback message for codes that name Object.prototype members', () => {
    const fallback = violationMessage('SOMETHING_NEW')
    expect(violationMessage('constructor')).toBe(fallback)
    expect(violationMessage('toString')).toBe(fallback)
    expect(violationMessage('__proto__')).toBe(fallback)
  })
})
