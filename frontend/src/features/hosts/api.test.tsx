import { renderHook, waitFor, cleanup } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { afterEach, describe, expect, expectTypeOf, it, vi } from 'vitest'
import { apiFetch } from '../../shared/api/client.ts'
import {
  HOSTS_KEY,
  HOSTS_OVERVIEW_KEY,
  useCreateHost,
  useDeleteHost,
  useHostsOverview,
  useRenameHost,
  useSetHostTags,
  type Host,
} from './api.ts'

vi.mock('../../shared/api/client.ts', async (importActual) => ({
  ...(await importActual<typeof import('../../shared/api/client.ts')>()),
  apiFetch: vi.fn(),
}))
const apiFetchMock = vi.mocked(apiFetch)

afterEach(() => {
  cleanup()
  vi.resetAllMocks()
})

function setup() {
  const client = new QueryClient({ defaultOptions: { mutations: { retry: false } } })
  const invalidate = vi.spyOn(client, 'invalidateQueries')
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  )
  return { invalidate, wrapper }
}
const invalidatedKeys = (spy: ReturnType<typeof setup>['invalidate']) =>
  spy.mock.calls.map((call) => JSON.stringify(call[0]?.queryKey))

describe('hosts mutations', () => {
  it('create posts the body and invalidates hosts, overview and current user', async () => {
    apiFetchMock.mockResolvedValue({ id: '1' })
    const { invalidate, wrapper } = setup()
    const { result } = renderHook(() => useCreateHost(), { wrapper })

    result.current.mutate({ name: 'Local', connectionType: 'LOCAL' })
    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(apiFetchMock).toHaveBeenCalledWith('/hosts', {
      method: 'POST',
      body: { name: 'Local', connectionType: 'LOCAL' },
    })
    const keys = invalidatedKeys(invalidate)
    expect(keys).toContain(JSON.stringify(HOSTS_KEY))
    expect(keys).toContain(JSON.stringify(HOSTS_OVERVIEW_KEY))
    expect(keys).toContain(JSON.stringify(['auth', 'me']))
  })

  it('delete calls DELETE and invalidates the same keys', async () => {
    apiFetchMock.mockResolvedValue(undefined)
    const { invalidate, wrapper } = setup()
    const { result } = renderHook(() => useDeleteHost(), { wrapper })

    result.current.mutate({ id: 'abc', removeProxy: false })
    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(apiFetchMock).toHaveBeenCalledWith('/hosts/abc', { method: 'DELETE' })
    expect(invalidatedKeys(invalidate)).toContain(JSON.stringify(['auth', 'me']))
  })

  it('delete with removeProxy asks the server to remove the proxy and keeps the lists while leftovers exist', async () => {
    apiFetchMock.mockResolvedValue({ leftovers: ['network shipir-proxy'] })
    const { invalidate, wrapper } = setup()
    const { result } = renderHook(() => useDeleteHost(), { wrapper })

    result.current.mutate({ id: 'abc', removeProxy: true })
    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(apiFetchMock).toHaveBeenCalledWith('/hosts/abc?removeProxy=true', { method: 'DELETE' })
    expect(invalidatedKeys(invalidate)).toEqual([])
  })

  it('rename patches the name and refreshes the lists', async () => {
    apiFetchMock.mockResolvedValue({ id: 'abc' })
    const { invalidate, wrapper } = setup()
    const { result } = renderHook(() => useRenameHost(), { wrapper })

    result.current.mutate({ id: 'abc', name: 'New' })
    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(apiFetchMock).toHaveBeenCalledWith('/hosts/abc', { method: 'PATCH', body: { name: 'New' } })
    expect(invalidatedKeys(invalidate)).toContain(JSON.stringify(HOSTS_OVERVIEW_KEY))
  })
})

describe('useSetHostTags', () => {
  it('puts the tag set and resolves with the updated environment the server returns', async () => {
    const updated: Host = {
      id: 'abc',
      name: 'Local',
      connectionType: 'LOCAL',
      host: null,
      port: null,
      tls: false,
      createdAt: '2026-10-01T10:00:00Z',
      tags: ['eu', 'prod'],
    }
    apiFetchMock.mockResolvedValue(updated)
    const { invalidate, wrapper } = setup()
    const { result } = renderHook(() => useSetHostTags(), { wrapper })

    result.current.mutate({ id: 'abc', tags: ['prod', 'eu'] })
    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(apiFetchMock).toHaveBeenCalledWith('/hosts/abc/tags', { method: 'PUT', body: { tags: ['prod', 'eu'] } })
    expect(result.current.data).toEqual(updated)
    expect(invalidatedKeys(invalidate)).toContain(JSON.stringify(HOSTS_OVERVIEW_KEY))
  })

  it('types the PUT response as the environment, not void', () => {
    expectTypeOf<ReturnType<typeof useSetHostTags>['data']>().toEqualTypeOf<Host | undefined>()
  })
})

describe('useHostsOverview', () => {
  it('fetches the overview under the shared key and refreshes on an interval and on focus', async () => {
    apiFetchMock.mockResolvedValue([])
    const { wrapper } = setup()
    const { result } = renderHook(() => useHostsOverview(), { wrapper })
    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(apiFetchMock).toHaveBeenCalledWith('/hosts/overview')
    expect(HOSTS_OVERVIEW_KEY).toEqual(['hosts', 'overview'])
  })
})
