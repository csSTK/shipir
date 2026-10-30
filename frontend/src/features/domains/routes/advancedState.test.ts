import { describe, expect, it } from 'vitest'
import { advancedPayload, checkAdvanced, initialAdvanced } from './advancedState.ts'
import type { ProxyRoute } from './api.ts'

const route: ProxyRoute = {
  id: 'r1',
  hostname: 'app.example.com',
  aliases: ['www.example.com'],
  containerName: 'web',
  targetPort: 8080,
  backendScheme: 'HTTPS',
  pathPrefix: '/app',
  entrypoint: 'websecure',
  certMode: 'ACME_HTTP',
  certificateId: null,
  redirectHttps: false,
  basicAuthUsers: ['admin'],
  responseHeaders: { 'X-Frame-Options': 'DENY' },
  advancedActive: true,
  containerState: 'running',
}

describe('advancedPayload', () => {
  it('sends nothing for a new route whose advanced fields are untouched', () => {
    const initial = initialAdvanced()
    expect(advancedPayload(initial, initial, false)).toEqual({})
  })

  it('sends only the touched fields of a new route', () => {
    const initial = initialAdvanced()
    const state = { ...initial, pathPrefix: ' /blog ', certMode: 'INTERNAL' as const }
    expect(advancedPayload(state, initial, false)).toEqual({ pathPrefix: '/blog', certMode: 'INTERNAL' })
  })

  it('sends every advanced field unchanged when editing, because PUT replaces them', () => {
    const initial = initialAdvanced(route)
    expect(advancedPayload(initial, initial, true)).toEqual({
      aliases: ['www.example.com'],
      backendScheme: 'HTTPS',
      pathPrefix: '/app',
      entrypoint: 'websecure',
      certMode: 'ACME_HTTP',
      certificateId: null,
      redirectHttps: false,
      responseHeaders: { 'X-Frame-Options': 'DENY' },
    })
  })

  it('omits basicAuth unless the users were touched, and keeps untouched users with an empty password', () => {
    const initial = initialAdvanced(route)
    expect(advancedPayload(initial, initial, true)).not.toHaveProperty('basicAuth')
    const added = { ...initial, users: [...initial.users, { key: 99, name: 'bob', password: 's3cret', existing: false }] }
    expect(advancedPayload(added, initial, true).basicAuth).toEqual({ admin: '', bob: 's3cret' })
    const removed = { ...initial, users: [] }
    expect(advancedPayload(removed, initial, true).basicAuth).toEqual({})
  })

  it('never prefills a password for existing users', () => {
    expect(initialAdvanced(route).users).toEqual([expect.objectContaining({ name: 'admin', password: '', existing: true })])
  })

  it('sends the certificate only for the own-certificate mode', () => {
    const initial = initialAdvanced()
    const custom = { ...initial, certMode: 'CUSTOM' as const, certificateId: 'c1' }
    expect(advancedPayload(custom, initial, false)).toEqual({ certMode: 'CUSTOM', certificateId: 'c1' })
    const stale = { ...initial, certMode: 'INTERNAL' as const, certificateId: 'c1' }
    expect(advancedPayload(stale, initial, false)).toEqual({ certMode: 'INTERNAL' })
  })
})

describe('checkAdvanced', () => {
  const ok = initialAdvanced()
  it('accepts an empty form', () => {
    expect(checkAdvanced(ok, { allowWildcard: false })).toEqual({})
  })
  it('flags a bad path prefix, alias, header and a user without a password', () => {
    const state = {
      ...ok,
      pathPrefix: 'blog',
      aliases: ['Bad_Name'],
      headers: [{ key: 1, name: 'X Bad', value: 'a"b' }],
      users: [{ key: 2, name: 'bob', password: '', existing: false }],
    }
    expect(Object.keys(checkAdvanced(state, { allowWildcard: false })).sort()).toEqual(['aliases', 'headers', 'pathPrefix', 'users'])
  })
  it('requires a certificate in own-certificate mode', () => {
    expect(checkAdvanced({ ...ok, certMode: 'CUSTOM' }, { allowWildcard: true })).toHaveProperty('certificateId')
  })
  it('allows wildcard aliases only when the mode covers them', () => {
    const state = { ...ok, aliases: ['*.example.com'] }
    expect(checkAdvanced(state, { allowWildcard: false })).toHaveProperty('aliases')
    expect(checkAdvanced(state, { allowWildcard: true })).toEqual({})
  })
})
