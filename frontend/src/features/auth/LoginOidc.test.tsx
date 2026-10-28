import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, apiFetch } from '../../shared/api/client.ts'
import { LoginPage } from './LoginPage.tsx'

vi.mock('../../shared/api/client.ts', async (importActual) => ({
  ...(await importActual<typeof import('../../shared/api/client.ts')>()),
  apiFetch: vi.fn(),
}))
const apiFetchMock = vi.mocked(apiFetch)
const assign = vi.fn()

beforeEach(() => {
  vi.stubGlobal('location', { ...window.location, assign, search: '', pathname: '/' })
})
afterEach(() => {
  cleanup()
  vi.resetAllMocks()
  vi.unstubAllGlobals()
})

function renderLogin(config: unknown, search = '') {
  vi.stubGlobal('location', { ...window.location, assign, search, pathname: '/' })
  apiFetchMock.mockImplementation((path: string) =>
    path === '/auth/oidc/config' ? Promise.resolve(config) : Promise.reject(new ApiError(401, 'no')),
  )
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <LoginPage />
    </QueryClientProvider>,
  )
}

describe('LoginPage single sign-on', () => {
  it('does not show the password form while the config is loading', async () => {
    apiFetchMock.mockImplementation(() => new Promise(() => {}))
    render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <LoginPage />
      </QueryClientProvider>,
    )
    expect(await screen.findByRole('heading', { name: 'Sign in to Shipir' })).toBeInTheDocument()
    expect(screen.queryByLabelText('Username')).not.toBeInTheDocument()
  })

  it('falls back to the password form when the config request fails', async () => {
    apiFetchMock.mockRejectedValue(new ApiError(500, 'boom'))
    render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <LoginPage />
      </QueryClientProvider>,
    )
    expect(await screen.findByLabelText('Username')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Sign in with/ })).not.toBeInTheDocument()
  })

  it('navigates (no fetch) to the authorize endpoint from the provider button', async () => {
    renderLogin({ enabled: true, displayName: 'id.example.com', localLoginEnabled: true })
    fireEvent.click(await screen.findByRole('button', { name: 'Sign in with id.example.com' }))
    expect(assign).toHaveBeenCalledWith('/api/auth/oidc/authorize')
    expect(screen.getByLabelText('Username')).toBeInTheDocument()
  })

  it('has no provider button when single sign-on is off', async () => {
    renderLogin({ enabled: false, displayName: null, localLoginEnabled: true })
    await screen.findByLabelText('Username')
    expect(screen.queryByRole('button', { name: /Sign in with/ })).not.toBeInTheDocument()
  })

  it('hides the password form when local login is off, with a way back to it', async () => {
    renderLogin({ enabled: true, displayName: 'id.example.com', localLoginEnabled: false })
    await screen.findByRole('button', { name: 'Sign in with id.example.com' })
    expect(screen.queryByLabelText('Username')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Use local login' }))
    expect(screen.getByLabelText('Username')).toBeInTheDocument()
  })

  it('maps oidc_error codes to a fixed message, never raw text', async () => {
    renderLogin({ enabled: true, displayName: 'id.example.com', localLoginEnabled: true }, '?oidc_error=ROLE_DENIED')
    expect(await screen.findByText(/groups do not give you access/)).toBeInTheDocument()
  })

  it('shows a specific message when the OIDC authorize endpoint rate limits the sign-in', async () => {
    renderLogin({ enabled: true, displayName: 'x', localLoginEnabled: true }, '?oidc_error=TOO_MANY_ATTEMPTS')
    expect(await screen.findByText('Too many sign-in attempts. Try again in a few minutes.')).toBeInTheDocument()
  })

  it('shows a specific message when SSO would remove the last enabled administrator', async () => {
    renderLogin({ enabled: true, displayName: 'x', localLoginEnabled: true }, '?oidc_error=LAST_ADMIN')
    expect(
      await screen.findByText(
        'Single sign-on would leave Shipir without an enabled administrator. Ask an administrator to check your group mapping.',
      ),
    ).toBeInTheDocument()
  })

  it('shows a generic message for an unknown code', async () => {
    renderLogin({ enabled: true, displayName: 'x', localLoginEnabled: true }, '?oidc_error=%3Cb%3Eevil')
    expect(await screen.findByText('Single sign-on failed. Please try again.')).toBeInTheDocument()
  })
})
