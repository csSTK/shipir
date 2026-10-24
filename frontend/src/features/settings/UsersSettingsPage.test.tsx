import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, apiFetch } from '../../shared/api/client.ts'
import { UsersSettingsPage } from './UsersSettingsPage.tsx'
import type { ManagedUser } from './api.ts'

vi.mock('../../shared/api/client.ts', async (importActual) => ({
  ...(await importActual<typeof import('../../shared/api/client.ts')>()),
  apiFetch: vi.fn(),
}))
const apiFetchMock = vi.mocked(apiFetch)

afterEach(() => {
  cleanup()
  vi.resetAllMocks()
})

const base = {
  passwordChangeRequired: false,
  createdAt: '2026-10-01T10:00:00Z',
  passwordChangedAt: '2026-10-03T10:00:00Z',
  disabledAt: null,
}
const me: ManagedUser = { ...base, id: 'u1', username: 'root', role: 'ADMIN', disabled: false }
const anna: ManagedUser = { ...base, id: 'u2', username: 'anna', role: 'OPERATOR', disabled: false }
const bob: ManagedUser = { ...base, id: 'u3', username: 'bob', role: 'VIEWER', disabled: true, disabledAt: '2026-10-02T10:00:00Z' }

function renderPage(handler?: (path: string, method: string, body: unknown) => unknown, list = [me, anna, bob]) {
  apiFetchMock.mockImplementation((path: string, options?: { method?: string; body?: unknown }) => {
    const method = options?.method ?? 'GET'
    if (method === 'GET' && path === '/users') return Promise.resolve(list)
    const result = handler?.(path, method, options?.body)
    return result instanceof Error ? Promise.reject(result) : Promise.resolve(result)
  })
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <UsersSettingsPage currentUsername="root" />
    </QueryClientProvider>,
  )
}

const calls = (method: string) =>
  apiFetchMock.mock.calls.filter(([, o]) => (o as { method?: string } | undefined)?.method === method)

describe('UsersSettingsPage', () => {
  it('lists users with role and status and hides self-actions', async () => {
    renderPage()
    expect(await screen.findByText('anna')).toBeTruthy()
    expect(screen.getByText('Disabled')).toBeTruthy()
    expect(screen.queryByRole('button', { name: 'Delete root' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Reset password for root' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Disable root' })).toBeNull()
    expect((screen.getByLabelText('Role for root') as HTMLSelectElement).disabled).toBe(true)
    expect(screen.getByRole('button', { name: 'Enable bob' })).toBeTruthy()
  })

  it('shows the password change date, or the pending badge until the user chooses a password', async () => {
    const dana: ManagedUser = {
      ...base,
      id: 'u4',
      username: 'dana',
      role: 'VIEWER',
      disabled: false,
      passwordChangeRequired: true,
      passwordChangedAt: null,
    }
    renderPage(undefined, [me, anna, dana])
    expect(await screen.findByText('dana')).toBeTruthy()
    expect(screen.getAllByText(/^Password changed .*2026/)).toHaveLength(2)
    expect(screen.getAllByText('Password change pending')).toHaveLength(1)
    expect(screen.queryByText(/^Created /)).toBeNull()
  })

  it('creates a user and shows the one-time password once', async () => {
    renderPage((path, method) =>
      method === 'POST' && path === '/users'
        ? { id: 'u9', username: 'carl', role: 'VIEWER', oneTimePassword: 'Secret-Pass-12345!' }
        : undefined,
    )
    await screen.findByText('anna')
    fireEvent.click(screen.getByRole('button', { name: 'Add user' }))
    fireEvent.change(screen.getByLabelText('Username'), { target: { value: 'carl' } })
    fireEvent.change(screen.getByLabelText('Role'), { target: { value: 'VIEWER' } })
    fireEvent.click(screen.getByRole('button', { name: 'Create user' }))
    expect(await screen.findByText('Secret-Pass-12345!')).toBeTruthy()
    expect(calls('POST')[0][1]).toMatchObject({ body: { username: 'carl', role: 'VIEWER' } })
    fireEvent.click(screen.getByRole('button', { name: 'Done' }))
    expect(screen.queryByText('Secret-Pass-12345!')).toBeNull()
  })

  it('shows USERNAME_TAKEN inline', async () => {
    renderPage((_path, method) =>
      method === 'POST' ? new ApiError(409, 'taken', 'USERNAME_TAKEN') : undefined,
    )
    await screen.findByText('anna')
    fireEvent.click(screen.getByRole('button', { name: 'Add user' }))
    fireEvent.change(screen.getByLabelText('Username'), { target: { value: 'anna' } })
    fireEvent.click(screen.getByRole('button', { name: 'Create user' }))
    expect((await screen.findByRole('alert')).textContent).toMatch(/already taken/)
  })

  it('changes a role', async () => {
    renderPage(() => ({ ...anna, role: 'ADMIN' }))
    await screen.findByText('anna')
    fireEvent.change(screen.getByLabelText('Role for anna'), { target: { value: 'ADMIN' } })
    await waitFor(() => expect(calls('PUT')).toHaveLength(1))
    expect(calls('PUT')[0][0]).toBe('/users/u2/role')
    expect(calls('PUT')[0][1]).toMatchObject({ body: { role: 'ADMIN' } })
  })

  it('shows LAST_ADMIN when a role change is refused', async () => {
    renderPage((_path, method) => (method === 'PUT' ? new ApiError(409, 'x', 'LAST_ADMIN') : undefined))
    await screen.findByText('anna')
    fireEvent.change(screen.getByLabelText('Role for anna'), { target: { value: 'VIEWER' } })
    expect((await screen.findByRole('alert')).textContent).toMatch(/enabled admin must remain/i)
  })

  it('disables and enables', async () => {
    renderPage(() => anna)
    await screen.findByText('anna')
    fireEvent.click(screen.getByRole('button', { name: 'Disable anna' }))
    await waitFor(() => expect(calls('POST').map((c) => c[0])).toContain('/users/u2/disable'))
    fireEvent.click(screen.getByRole('button', { name: 'Enable bob' }))
    await waitFor(() => expect(calls('POST').map((c) => c[0])).toContain('/users/u3/enable'))
  })

  it('resets a password after confirmation and shows the new one-time password', async () => {
    renderPage(() => ({ id: 'u2', username: 'anna', role: 'OPERATOR', oneTimePassword: 'Fresh-Pass-98765!' }))
    await screen.findByText('anna')
    fireEvent.click(screen.getByRole('button', { name: 'Reset password for anna' }))
    expect(calls('POST')).toHaveLength(0)
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Reset password' }))
    expect(await screen.findByText('Fresh-Pass-98765!')).toBeTruthy()
  })

  it('deletes after confirmation and shows USER_HAS_HISTORY inline', async () => {
    renderPage((_path, method) => (method === 'DELETE' ? new ApiError(409, 'x', 'USER_HAS_HISTORY') : undefined))
    await screen.findByText('anna')
    fireEvent.click(screen.getByRole('button', { name: 'Delete anna' }))
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Delete' }))
    expect((await screen.findByRole('alert')).textContent).toMatch(/recorded activity/i)
    expect(calls('DELETE')[0][0]).toBe('/users/u2')
  })
})
