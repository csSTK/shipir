import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, cleanup, renderHook } from '@testing-library/react'
import type { ReactNode } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useAdvancedMode } from './advancedMode.ts'

const apiFetch = vi.fn()
vi.mock('../../shared/api/client.ts', async (importActual) => ({
  ...(await importActual<typeof import('../../shared/api/client.ts')>()),
  apiFetch: (...args: unknown[]) => apiFetch(...args),
}))

function wrapper({ children }: { children: ReactNode }) {
  return <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>{children}</QueryClientProvider>
}

const settle = () => act(async () => void (await new Promise((r) => setTimeout(r, 20))))
let username = 'alice'
beforeEach(() => {
  localStorage.clear()
  username = 'alice'
  apiFetch.mockImplementation((path: string) => (path === '/auth/me' ? Promise.resolve({ username, role: 'ADMIN', setupRequired: false }) : Promise.resolve(null)))
})
afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  apiFetch.mockReset()
})

describe('useAdvancedMode', () => {
  it('is off by default and remembers the choice in the browser', async () => {
    const first = renderHook(() => useAdvancedMode(), { wrapper })
    expect(first.result.current[0]).toBe(false)
    await settle()
    act(() => first.result.current[1](true))
    expect(first.result.current[0]).toBe(true)
    first.unmount()
    const second = renderHook(() => useAdvancedMode(), { wrapper })
    await vi.waitFor(() => expect(second.result.current[0]).toBe(true))
  })

  it('is not ready and stores nothing until the user is known', async () => {
    apiFetch.mockImplementation(() => new Promise(() => {}))
    const { result } = renderHook(() => useAdvancedMode(), { wrapper })
    expect(result.current[2]).toBe(false)
    act(() => result.current[1](true))
    expect(result.current[0]).toBe(false)
    expect(localStorage.length).toBe(0)
  })

  it('keeps the choice per user', async () => {
    const a = renderHook(() => useAdvancedMode(), { wrapper })
    await settle()
    act(() => a.result.current[1](true))
    a.unmount()
    username = 'bob'
    const b = renderHook(() => useAdvancedMode(), { wrapper })
    await new Promise((r) => setTimeout(r, 20))
    expect(b.result.current[0]).toBe(false)
  })

  it('still works when the browser storage is blocked', async () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked')
    })
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('blocked')
    })
    const { result } = renderHook(() => useAdvancedMode(), { wrapper })
    expect(result.current[0]).toBe(false)
    await settle()
    act(() => result.current[1](true))
    expect(result.current[0]).toBe(true)
  })
})
