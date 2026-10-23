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
})
