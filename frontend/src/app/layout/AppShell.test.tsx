import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { CurrentUser } from '../../features/auth/index.ts'
import { QueryClientProvider } from '@tanstack/react-query'
import { queryClient } from '../queryClient.ts'
import { AppShell } from './AppShell.tsx'

vi.mock('../../shared/api/client.ts', async (importActual) => ({
  ...(await importActual<typeof import('../../shared/api/client.ts')>()),
  apiFetch: vi.fn(async (path: string) =>
    path.startsWith('/hosts/overview') ? [{ id: 'h1', name: 'Prod' }] : { count: 3 },
  ),
}))

afterEach(cleanup)

const user = (role: CurrentUser['role']): CurrentUser => ({
  username: 'pat',
  role,
  setupRequired: false,
})

function Where() {
  return <p data-testid="where">{useLocation().pathname}</p>
}

function renderShell(role: CurrentUser['role'], onSignOut = vi.fn(), path = '/') {
  render(
    <QueryClientProvider client={queryClient}>
    <MemoryRouter initialEntries={[path]}>
      <AppShell user={user(role)} onSignOut={onSignOut}>
        <Routes>
          <Route path="*" element={<Where />} />
        </Routes>
      </AppShell>
    </MemoryRouter>
    </QueryClientProvider>,
  )
  return onSignOut
}

describe('AppShell', () => {
  it('shows the unread count on the Notifications item', async () => {
    renderShell('VIEWER')
    expect(await screen.findByLabelText('3 unread')).toBeInTheDocument()
  })

  it('shows the Settings group to ADMIN only', () => {
    renderShell('ADMIN')
    const group = screen.getByRole('group', { name: 'Settings' })
    expect(within(group).getByRole('link', { name: 'Retention' })).toBeInTheDocument()
    expect(within(group).getByRole('link', { name: 'Audit log' })).toBeInTheDocument()
    expect(within(group).getByRole('link', { name: 'Git credentials' })).toBeInTheDocument()
    cleanup()
    for (const role of ['OPERATOR', 'VIEWER'] as const) {
      renderShell(role)
      expect(screen.queryByRole('group', { name: 'Settings' })).not.toBeInTheDocument()
      expect(screen.getByRole('link', { name: 'Environments' })).toBeInTheDocument()
      cleanup()
    }
  })

  it('highlights the active item', () => {
    renderShell('VIEWER')
    expect(screen.getByRole('link', { name: 'Environments' })).toHaveAttribute('aria-current', 'page')
    fireEvent.click(screen.getByRole('link', { name: /^Notifications/ }))
    expect(screen.getByTestId('where')).toHaveTextContent('/notifications')
    expect(screen.getByRole('link', { name: /^Notifications/ })).toHaveAttribute('aria-current', 'page')
  })

  it('opens and closes the user menu with mouse and keyboard', () => {
    renderShell('ADMIN')
    const trigger = screen.getByRole('button', { name: /pat/ })
    expect(trigger).toHaveAttribute('aria-expanded', 'false')
    fireEvent.click(trigger)
    expect(trigger).toHaveAttribute('aria-expanded', 'true')
    const settings = screen.getByRole('menuitem', { name: 'Settings' })
    const signOut = screen.getByRole('menuitem', { name: 'Sign out' })
    expect(settings).toHaveFocus()
    fireEvent.keyDown(settings, { key: 'ArrowDown' })
    expect(signOut).toHaveFocus()
    fireEvent.keyDown(signOut, { key: 'ArrowDown' })
    expect(settings).toHaveFocus()
    fireEvent.keyDown(settings, { key: 'ArrowUp' })
    expect(signOut).toHaveFocus()
    fireEvent.keyDown(signOut, { key: 'Escape' })
    expect(screen.queryByRole('menu')).not.toBeInTheDocument()
    expect(trigger).toHaveFocus()
    fireEvent.click(trigger)
    fireEvent.click(trigger)
    expect(screen.queryByRole('menu')).not.toBeInTheDocument()
    fireEvent.click(trigger)
    fireEvent.pointerDown(document.body)
    expect(screen.queryByRole('menu')).not.toBeInTheDocument()
  })

  it('Sign out calls the handler', () => {
    const onSignOut = renderShell('VIEWER')
    fireEvent.click(screen.getByRole('button', { name: /pat/ }))
    fireEvent.click(screen.getByRole('menuitem', { name: 'Sign out' }))
    expect(onSignOut).toHaveBeenCalledOnce()
  })

  it('Settings navigates to /account', () => {
    renderShell('VIEWER')
    fireEvent.click(screen.getByRole('button', { name: /pat/ }))
    fireEvent.click(screen.getByRole('menuitem', { name: 'Settings' }))
    expect(screen.getByTestId('where')).toHaveTextContent('/account')
    expect(screen.queryByRole('menu')).not.toBeInTheDocument()
  })

  it('shows the environment section only on environment routes', async () => {
    renderShell('VIEWER')
    expect(screen.queryByRole('group', { name: 'Prod' })).not.toBeInTheDocument()
    cleanup()
    renderShell('VIEWER', vi.fn(), '/environments/h1/images')
    const group = await screen.findByRole('group', { name: 'Prod' })
    for (const name of ['Dashboard', 'Deployments', 'Containers', 'Images', 'Networks', 'Volumes']) {
      expect(within(group).getByRole('link', { name })).toBeInTheDocument()
    }
    expect(within(group).getByRole('link', { name: 'Images' })).toHaveAttribute('aria-current', 'page')
    expect(within(group).getByRole('link', { name: 'Dashboard' })).not.toHaveAttribute('aria-current')
    fireEvent.click(within(group).getByRole('link', { name: 'Volumes' }))
    expect(screen.getByTestId('where')).toHaveTextContent('/environments/h1/volumes')
  })
})
