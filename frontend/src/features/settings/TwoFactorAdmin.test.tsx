import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, apiFetch } from '../../shared/api/client.ts'
import { UsersSettingsPage } from './UsersSettingsPage.tsx'
import { AUDIT_ACTION_LABELS } from './api.ts'

vi.mock('../../shared/api/client.ts', async (importActual) => ({
  ...(await importActual<typeof import('../../shared/api/client.ts')>()),
  apiFetch: vi.fn(),
}))
const apiFetchMock = vi.mocked(apiFetch)
afterEach(() => {
  cleanup()
  vi.resetAllMocks()
})

const base = { disabled: false, passwordChangeRequired: false, createdAt: '2026-10-01T10:00:00Z', passwordChangedAt: '2026-10-03T10:00:00Z', disabledAt: null }
const me = { ...base, id: 'u1', username: 'root', role: 'ADMIN', twoFactorEnabled: true }
const anna = { ...base, id: 'u2', username: 'anna', role: 'OPERATOR', twoFactorEnabled: true }
const bob = { ...base, id: 'u3', username: 'bob', role: 'VIEWER', twoFactorEnabled: false }

function renderPage(handler: (key: string, body: unknown) => unknown = () => undefined) {
  apiFetchMock.mockImplementation(((path: string, o?: { method?: string; body?: unknown }) => {
    const key = `${o?.method ?? 'GET'} ${path}`
    if (key === 'GET /users') return Promise.resolve([me, anna, bob])
    if (key === 'GET /settings/security') return Promise.resolve({ require2faForAdmins: false, updatedAt: null })
    const r = handler(key, o?.body)
    return r instanceof Error ? Promise.reject(r) : Promise.resolve(r)
  }) as typeof apiFetch)
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <UsersSettingsPage currentUsername="root" />
    </QueryClientProvider>,
  )
}

describe('Users page two-factor admin features', () => {
  it('resets 2FA after confirmation, only for users that have it and not for self', async () => {
    renderPage()
    await screen.findByText('anna')
    expect(screen.queryByRole('button', { name: 'Reset 2FA for root' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Reset 2FA for bob' })).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Reset 2FA for anna' }))
    fireEvent.click(await screen.findByRole('button', { name: 'Reset 2FA' }))
    await waitFor(() => expect(apiFetchMock).toHaveBeenCalledWith('/users/u2/reset-2fa', { method: 'POST' }))
  })

  it('saves the require-2FA switch', async () => {
    renderPage((key) => (key === 'PUT /settings/security' ? { require2faForAdmins: true, updatedAt: 'x' } : undefined))
    const toggle = await screen.findByRole('switch', { name: 'Require 2FA for ADMINs' })
    fireEvent.click(toggle)
    await waitFor(() =>
      expect(apiFetchMock).toHaveBeenCalledWith('/settings/security', { method: 'PUT', body: { require2faForAdmins: true } }),
    )
  })

  it('shows an error when saving the switch fails', async () => {
    renderPage((key) => (key === 'PUT /settings/security' ? new ApiError(500, 'x', 'X') : undefined))
    fireEvent.click(await screen.findByRole('switch', { name: 'Require 2FA for ADMINs' }))
    expect(await screen.findByText(/could not save/i)).toBeInTheDocument()
  })

  it('labels the 2FA audit actions', () => {
    for (const a of ['MFA_ENABLE', 'MFA_DISABLE', 'MFA_VERIFY', 'MFA_RESET', 'SECURITY_SETTINGS_UPDATE']) {
      expect(AUDIT_ACTION_LABELS[a]).toBeTruthy()
    }
  })
})
