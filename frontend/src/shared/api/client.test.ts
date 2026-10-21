import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, apiFetch } from './client.ts'

afterEach(() => vi.unstubAllGlobals())

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
})
