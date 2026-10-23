import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { CurrentUser } from '../../features/auth/index.ts'
import { AppShell } from './AppShell.tsx'

afterEach(cleanup)

const user = (role: CurrentUser['role']): CurrentUser => ({
  username: 'pat',
  role,
  setupRequired: false,
})

function Where() {
  return <p data-testid="where">{useLocation().pathname}</p>
}

function renderShell(role: CurrentUser['role'], onSignOut = vi.fn()) {
  render(
    <MemoryRouter initialEntries={['/']}>
      <AppShell user={user(role)} onSignOut={onSignOut}>
        <Routes>
          <Route path="*" element={<Where />} />
        </Routes>
      </AppShell>
    </MemoryRouter>,
  )
  return onSignOut
}

describe('AppShell', () => {
  it('shows the Settings group to ADMIN only', () => {
    renderShell('ADMIN')
    const group = screen.getByRole('group', { name: 'Settings' })
    expect(within(group).getByRole('link', { name: 'Retention' })).toBeInTheDocument()
    expect(within(group).getByRole('link', { name: 'Audit log' })).toBeInTheDocument()
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
    fireEvent.click(screen.getByRole('link', { name: 'Notifications' }))
    expect(screen.getByTestId('where')).toHaveTextContent('/notifications')
    expect(screen.getByRole('link', { name: 'Notifications' })).toHaveAttribute('aria-current', 'page')
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
})
