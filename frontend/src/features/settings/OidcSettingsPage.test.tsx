import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, apiFetch } from '../../shared/api/client.ts'
import { OidcSettingsPage } from './OidcSettingsPage.tsx'

vi.mock('../../shared/api/client.ts', async (importActual) => ({
  ...(await importActual<typeof import('../../shared/api/client.ts')>()),
  apiFetch: vi.fn(),
}))
const apiFetchMock = vi.mocked(apiFetch)

afterEach(() => {
  cleanup()
  vi.resetAllMocks()
})

const current = {
  issuerUrl: 'https://id.example.com',
  clientId: 'shipir',
  clientSecretConfigured: true,
  clientSecretHint: '…ab12',
  scopes: 'openid profile email',
  groupsClaim: 'groups',
  roleMapping: { ADMIN: ['admins'], OPERATOR: ['ops', 'dev'], VIEWER: [] },
  autoProvision: false,
  localLoginEnabled: true,
  enabled: true,
  redirectUri: 'https://shipir.example.com/api/auth/oidc/callback',
}

function renderPage(handler?: (path: string, method: string, body: unknown) => unknown) {
  apiFetchMock.mockImplementation((path: string, options?: { method?: string; body?: unknown }) => {
    const method = options?.method ?? 'GET'
    if (method === 'GET' && path === '/settings/oidc') return Promise.resolve(current)
    const result = handler?.(path, method, options?.body)
    return result instanceof Error ? Promise.reject(result) : Promise.resolve(result)
  })
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <OidcSettingsPage />
    </QueryClientProvider>,
  )
}

describe('OidcSettingsPage', () => {
  it('shows the stored values but never the secret', async () => {
    renderPage()
    expect(await screen.findByLabelText('Issuer URL')).toHaveValue('https://id.example.com')
    expect(screen.getByLabelText('Client secret')).toHaveValue('')
    expect(screen.getByText(/…ab12/)).toBeInTheDocument()
    expect(screen.getByLabelText('Operator groups')).toHaveValue('ops, dev')
    expect(screen.getByText('https://shipir.example.com/api/auth/oidc/callback')).toBeInTheDocument()
  })

  it('omits the secret when empty and sends parsed role groups', async () => {
    const calls: unknown[] = []
    renderPage((_p, method, body) => {
      if (method === 'PUT') {
        calls.push(body)
        return current
      }
    })
    fireEvent.change(await screen.findByLabelText('Viewer groups'), { target: { value: 'all, staff ,' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    expect(await screen.findByText('Single sign-on settings saved.')).toBeInTheDocument()
    const body = calls[0] as Record<string, unknown>
    expect(body).not.toHaveProperty('clientSecret')
    expect(body.roleMapping).toEqual({ ADMIN: ['admins'], OPERATOR: ['ops', 'dev'], VIEWER: ['all', 'staff'] })
  })

  it('sends an empty string to clear the secret', async () => {
    const calls: unknown[] = []
    renderPage((_p, method, body) => {
      if (method === 'PUT') {
        calls.push(body)
        return current
      }
    })
    fireEvent.click(await screen.findByRole('button', { name: 'Remove stored secret' }))
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(calls).toHaveLength(1))
    expect((calls[0] as { clientSecret: string }).clientSecret).toBe('')
  })

  it('shows LOCAL_LOGIN_REQUIRED inline', async () => {
    renderPage((_p, method) => (method === 'PUT' ? new ApiError(400, 'x', 'LOCAL_LOGIN_REQUIRED') : undefined))
    fireEvent.click(await screen.findByRole('button', { name: 'Save' }))
    expect(await screen.findByRole('alert')).toHaveTextContent(/Local login can only be switched off/)
  })

  it('reports the test result', async () => {
    renderPage((p) => (p === '/settings/oidc/test' ? { status: 'UNREACHABLE' } : undefined))
    fireEvent.click(await screen.findByRole('button', { name: 'Test connection' }))
    expect(await screen.findByText('The provider could not be reached.')).toBeInTheDocument()
  })
})
