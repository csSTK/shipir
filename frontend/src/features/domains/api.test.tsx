import { renderHook, waitFor, cleanup } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { apiFetch } from '../../shared/api/client.ts'
import { useManagedProxy } from './api.ts'

vi.mock('../../shared/api/client.ts', async (importActual) => ({
  ...(await importActual<typeof import('../../shared/api/client.ts')>()),
  apiFetch: vi.fn(),
}))
const apiFetchMock = vi.mocked(apiFetch)

afterEach(() => {
  cleanup()
  vi.resetAllMocks()
})

function wrapper() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return ({ children }: { children: ReactNode }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>
}

describe('useManagedProxy', () => {
  it('reads the managed proxy of the host by default', async () => {
    apiFetchMock.mockResolvedValue({ instance: null, setupBlockedReason: null })

    const { result } = renderHook(() => useManagedProxy('h1'), { wrapper: wrapper() })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(apiFetchMock).toHaveBeenCalledWith('/hosts/h1/proxy/managed')
  })

  it('does not fetch while enabled is false', async () => {
    const { result } = renderHook(() => useManagedProxy('h1', { enabled: false }), { wrapper: wrapper() })

    expect(result.current.fetchStatus).toBe('idle')
    expect(apiFetchMock).not.toHaveBeenCalled()
  })

  it('retries a failed read by default and not at all with retry: false', async () => {
    // The client retries twice by default, so the option is what differs between the two hooks.
    const retryingWrapper = () => {
      const client = new QueryClient({ defaultOptions: { queries: { retry: 2, retryDelay: 0 } } })
      return ({ children }: { children: ReactNode }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>
    }
    apiFetchMock.mockRejectedValue(new Error('boom'))

    const retried = renderHook(() => useManagedProxy('h1'), { wrapper: retryingWrapper() })
    await waitFor(() => expect(retried.result.current.isError).toBe(true))
    expect(apiFetchMock).toHaveBeenCalledTimes(3)

    apiFetchMock.mockClear()
    const notRetried = renderHook(() => useManagedProxy('h2', { retry: false }), { wrapper: retryingWrapper() })
    await waitFor(() => expect(notRetried.result.current.isError).toBe(true))
    expect(apiFetchMock).toHaveBeenCalledTimes(1)
  })
})
