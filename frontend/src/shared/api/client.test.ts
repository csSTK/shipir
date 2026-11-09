import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, apiFetch } from './client.ts'

afterEach(() => {
  vi.unstubAllGlobals()
  document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/'
})

describe('apiFetch', () => {
  it('calls a relative /api path and parses JSON', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ ok: true })))
    vi.stubGlobal('fetch', fetchMock)

    await expect(apiFetch<{ ok: boolean }>('/containers')).resolves.toEqual({ ok: true })
    expect(fetchMock).toHaveBeenCalledWith('/api/containers', expect.objectContaining({ method: 'GET' }))
  })

  it('sends a JSON body', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 204 }))
    vi.stubGlobal('fetch', fetchMock)

    await apiFetch('/hosts', { method: 'POST', body: { name: 'local' } })
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/hosts',
      expect.objectContaining({ body: '{"name":"local"}' }),
    )
  })

  it('throws ApiError on non-2xx responses', async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(new Response('nope', { status: 404 }))))

    await expect(apiFetch('/missing')).rejects.toMatchObject({ name: 'ApiError', status: 404 })
    await expect(apiFetch('/missing')).rejects.toBeInstanceOf(ApiError)
  })

  it('sends the XSRF-TOKEN cookie as X-XSRF-TOKEN on POST', async () => {
    document.cookie = 'XSRF-TOKEN=abc%3D123; path=/'
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 204 }))
    vi.stubGlobal('fetch', fetchMock)

    await apiFetch('/auth/logout', { method: 'POST' })
    const init = fetchMock.mock.calls[0][1] as RequestInit
    expect((init.headers as Record<string, string>)['X-XSRF-TOKEN']).toBe('abc=123')
    expect(init.credentials).toBe('same-origin')
  })

  it('does not send X-XSRF-TOKEN on GET', async () => {
    document.cookie = 'XSRF-TOKEN=abc; path=/'
    const fetchMock = vi.fn().mockResolvedValue(new Response('{}'))
    vi.stubGlobal('fetch', fetchMock)

    await apiFetch('/auth/me')
    const init = fetchMock.mock.calls[0][1] as RequestInit
    expect(init.headers).not.toHaveProperty('X-XSRF-TOKEN')
  })

  it('exposes the error code from a JSON error body', async () => {
    const body = JSON.stringify({ code: 'SETUP_REQUIRED' })
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(new Response(body, { status: 403 }))))

    await expect(apiFetch('/x')).rejects.toMatchObject({ status: 403, code: 'SETUP_REQUIRED' })
  })

  it('exposes code and violations from a JSON error body', async () => {
    const body = { code: 'PASSWORD_POLICY', violations: ['TOO_SHORT', 'MISSING_DIGIT'] }
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(new Response(JSON.stringify(body), { status: 400 }))))

    await expect(apiFetch('/auth/password', { method: 'POST', body: {} })).rejects.toMatchObject({
      status: 400,
      code: 'PASSWORD_POLICY',
      violations: ['TOO_SHORT', 'MISSING_DIGIT'],
    })
  })

  it('ignores a violations field that is not a string array', async () => {
    const body = { code: 'X', violations: [1, 2] }
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(new Response(JSON.stringify(body), { status: 400 }))))

    await expect(apiFetch('/x')).rejects.toMatchObject({ code: 'X', violations: undefined })
  })
})

describe('apiFetch error details', () => {
  it('exposes the details array of an error body', async () => {
    const body = { code: 'VALIDATION_ERROR', message: 'bad', details: ['port: must be positive'] }
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(new Response(JSON.stringify(body), { status: 400 }))))

    await expect(apiFetch('/hosts')).rejects.toMatchObject({
      code: 'VALIDATION_ERROR',
      details: ['port: must be positive'],
    })
  })
})

describe('apiFetch Retry-After', () => {
  it('exposes a numeric Retry-After header as retryAfterSeconds on 429', async () => {
    const response = new Response('{"code":"TOO_MANY_ATTEMPTS"}', { status: 429, headers: { 'Retry-After': '42' } })
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(response)))

    await expect(apiFetch('/auth/login', { method: 'POST', body: {} })).rejects.toMatchObject({
      status: 429,
      code: 'TOO_MANY_ATTEMPTS',
      retryAfterSeconds: 42,
    })
  })

  it('ignores a Retry-After header that is not a number of seconds', async () => {
    const response = new Response('{}', { status: 429, headers: { 'Retry-After': 'Wed, 21 Oct 2026 07:28:00 GMT' } })
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(response)))

    await expect(apiFetch('/auth/login', { method: 'POST', body: {} })).rejects.toMatchObject({
      retryAfterSeconds: undefined,
    })
  })

  it('leaves retryAfterSeconds undefined without the header', async () => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(new Response('{}', { status: 500 }))))

    await expect(apiFetch('/x')).rejects.toMatchObject({ retryAfterSeconds: undefined })
  })

  it('exposes the message field of a JSON error body as serverMessage', async () => {
    const body = JSON.stringify({ code: 'INVALID_PROXY_CONFIG', message: 'line 3: unexpected }' })
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(new Response(body, { status: 422 }))))
    await expect(apiFetch('/x')).rejects.toMatchObject({ serverMessage: 'line 3: unexpected }', message: body })
  })

  it('has no serverMessage for plain text, blank or missing messages', async () => {
    for (const body of ['nope', JSON.stringify({ message: '  ' }), JSON.stringify({ code: 'X' })]) {
      vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(new Response(body, { status: 500 }))))
      await expect(apiFetch('/x')).rejects.toMatchObject({ serverMessage: undefined })
    }
  })
})
