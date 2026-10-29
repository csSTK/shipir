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
    if (method === 'GET' && path === '/settings/security') return Promise.resolve({ require2faForAdmins: false, updatedAt: null })
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

  it('disables the password reset for SSO users and keeps it for local users', async () => {
    const sso: ManagedUser = { ...base, id: 'u4', username: 'sam', role: 'OPERATOR', disabled: false, externalIdentity: true }
    renderPage(undefined, [me, anna, sso])
    expect(await screen.findByText('sam')).toBeTruthy()
    expect((screen.getByRole('button', { name: 'Password managed by SSO for sam' }) as HTMLButtonElement).disabled).toBe(true)
    expect(screen.queryByRole('button', { name: 'Reset password for sam' })).toBeNull()
    expect((screen.getByRole('button', { name: 'Reset password for anna' }) as HTMLButtonElement).disabled).toBe(false)
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

  describe('access', () => {
    const host = { id: 'h1', name: 'prod', connectionType: 'LOCAL', host: null, port: null, tls: false, createdAt: '2026-10-01T10:00:00Z' }
    const dep = { id: 'd1', name: 'web', status: 'RUNNING', source: 'FILE', lastDeployedAt: null, createdAt: '2026-10-01T10:00:00Z' }
    function access(initial: unknown[]) {
      renderPage((path, method) => {
        if (path === '/users/u2/permissions') return method === 'PUT' ? { grants: [] } : { grants: initial }
        if (path === '/hosts') return [host]
        if (path === '/hosts/h1/managed-deployments') return [dep]
        return undefined
      })
    }
    const put = () => calls('PUT').find(([p]) => p === '/users/u2/permissions')

    it('hides the Access action for admins', async () => {
      access([])
      await screen.findByText('anna')
      expect(screen.queryByRole('button', { name: 'Access for root' })).toBeNull()
      expect(screen.getByRole('button', { name: 'Access for anna' })).toBeTruthy()
    })

    it('grants host and deployment levels and saves them via PUT', async () => {
      access([])
      await screen.findByText('anna')
      fireEvent.click(screen.getByRole('button', { name: 'Access for anna' }))
      const dialog = await screen.findByRole('dialog')
      fireEvent.change(await within(dialog).findByLabelText('Access to web'), { target: { value: 'OPERATE' } })
      fireEvent.click(within(dialog).getByRole('button', { name: 'Save access' }))
      await waitFor(() => expect(put()).toBeTruthy())
      expect(put()![1]).toMatchObject({ body: { grants: [{ deploymentId: 'd1', level: 'OPERATE' }] } })
      await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    })

    it('shows existing grants, implies deployments under a host grant and revokes with an empty set', async () => {
      access([{ hostId: 'h1', deploymentId: null, deploymentHostId: null, level: 'VIEW' }])
      await screen.findByText('anna')
      fireEvent.click(screen.getByRole('button', { name: 'Access for anna' }))
      const dialog = await screen.findByRole('dialog')
      const hostSelect = (await within(dialog).findByLabelText('Access to prod')) as HTMLSelectElement
      expect(hostSelect.value).toBe('VIEW')
      expect(((await within(dialog).findByLabelText('Access to web')) as HTMLSelectElement).disabled).toBe(true)
      fireEvent.change(hostSelect, { target: { value: 'NONE' } })
      expect((within(dialog).getByLabelText('Access to web') as HTMLSelectElement).disabled).toBe(false)
      fireEvent.click(within(dialog).getByRole('button', { name: 'Save access' }))
      await waitFor(() => expect(put()).toBeTruthy())
      expect(put()![1]).toMatchObject({ body: { grants: [] } })
    })

    it('saves a host grant', async () => {
      access([])
      await screen.findByText('anna')
      fireEvent.click(screen.getByRole('button', { name: 'Access for anna' }))
      const dialog = await screen.findByRole('dialog')
      fireEvent.change(await within(dialog).findByLabelText('Access to prod'), { target: { value: 'OPERATE' } })
      fireEvent.click(within(dialog).getByRole('button', { name: 'Save access' }))
      await waitFor(() => expect(put()).toBeTruthy())
      expect(put()![1]).toMatchObject({ body: { grants: [{ hostId: 'h1', level: 'OPERATE' }] } })
    })

    it('mentions the view cap for viewers and shows server errors inline', async () => {
      renderPage((path, method) => {
        if (path === '/users/u3/permissions') return method === 'PUT' ? new ApiError(400, 'x', 'UNKNOWN_HOST') : { grants: [] }
        if (path === '/hosts') return [host]
        if (path === '/hosts/h1/managed-deployments') return []
        return undefined
      })
      await screen.findByText('bob')
      fireEvent.click(screen.getByRole('button', { name: 'Access for bob' }))
      const dialog = await screen.findByRole('dialog')
      expect(within(dialog).getByText(/limited to view/i)).toBeTruthy()
      fireEvent.change(await within(dialog).findByLabelText('Access to prod'), { target: { value: 'VIEW' } })
      fireEvent.click(within(dialog).getByRole('button', { name: 'Save access' }))
      expect((await within(dialog).findByRole('alert')).textContent).toMatch(/no longer exists/i)
    })
  })
})
