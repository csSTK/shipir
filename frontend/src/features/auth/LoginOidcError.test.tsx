import { StrictMode } from 'react'
import { cleanup, render, screen } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, apiFetch } from '../../shared/api/client.ts'
import { LoginPage } from './LoginPage.tsx'

vi.mock('../../shared/api/client.ts', async (importActual) => ({
  ...(await importActual<typeof import('../../shared/api/client.ts')>()),
  apiFetch: vi.fn(),
}))
const apiFetchMock = vi.mocked(apiFetch)

beforeEach(() => {
  apiFetchMock.mockImplementation((path: string) =>
    path === '/auth/oidc/config'
      ? Promise.resolve({ enabled: true, displayName: 'x', localLoginEnabled: true })
      : Promise.reject(new ApiError(401, 'no')),
  )
})
afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  vi.resetAllMocks()
  window.history.replaceState(null, '', '/')
})

describe('LoginPage oidc_error handling', () => {
  it('shows the error and cleans the URL exactly once under StrictMode', async () => {
    window.history.replaceState(null, '', '/?oidc_error=ROLE_DENIED')
    const originalReplaceState = window.history.replaceState.bind(window.history)
    // Error text already in the DOM when the URL is cleaned: cleaning happens after commit, not during render.
    const alertTextAtClean: Array<string | undefined> = []
    const replaceState = vi.spyOn(window.history, 'replaceState').mockImplementation((...args) => {
      alertTextAtClean.push(document.querySelector('[role="alert"]')?.textContent ?? undefined)
      originalReplaceState(...args)
    })

    render(
      <StrictMode>
        <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
          <LoginPage />
        </QueryClientProvider>
      </StrictMode>,
    )

    expect(await screen.findByText(/groups do not give you access/)).toBeInTheDocument()
    expect(window.location.search).toBe('')
    expect(replaceState).toHaveBeenCalledTimes(1)
    expect(alertTextAtClean[0]).toMatch(/groups do not give you access/)
  })
})
