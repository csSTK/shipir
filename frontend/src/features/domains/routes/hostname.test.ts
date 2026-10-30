import { describe, expect, it } from 'vitest'
import { checkHostname } from './hostname.ts'

describe('checkHostname (mirrors the backend rules)', () => {
  it.each(['app.example.com', 'a.b', 'xn--bcher-kva.example', 'my-app.example.co.uk', '1.example.com'])('accepts %s', (name) => {
    expect(checkHostname(name)).toBeNull()
  })

  it('needs a value', () => {
    expect(checkHostname('   ')).toBe('required')
  })

  it.each(['localhost', 'example', '-a.example.com', 'a-.example.com', 'a..example.com', 'a_b.example.com', 'exa mple.com', 'https://a.example.com', 'a.example.com/'])(
    'rejects %s',
    (name) => {
      expect(checkHostname(name)).toBe('invalid')
    },
  )

  it('asks for lower case', () => {
    expect(checkHostname('App.Example.com')).toBe('uppercase')
  })

  it('limits label and total length', () => {
    expect(checkHostname(`${'a'.repeat(63)}.com`)).toBeNull()
    expect(checkHostname(`${'a'.repeat(64)}.com`)).toBe('invalid')
    expect(checkHostname(`${'a'.repeat(60)}.`.repeat(5) + 'com')).toBe('tooLong')
  })

  it('allows a wildcard only when the certificate mode can cover it', () => {
    expect(checkHostname('*.example.com')).toBe('wildcardNotAllowed')
    expect(checkHostname('*.example.com', true)).toBeNull()
    expect(checkHostname('*.*.example.com', true)).toBe('invalid')
    expect(checkHostname('a.*.example.com', true)).toBe('invalid')
  })
})
