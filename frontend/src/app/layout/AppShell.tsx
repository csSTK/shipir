import { useState, type ReactNode } from 'react'
import type { CurrentUser } from '../../features/auth/index.ts'
import { Sidebar } from './Sidebar.tsx'
import { UserMenu } from './UserMenu.tsx'
import './AppShell.css'

interface Props {
  user: CurrentUser
  onSignOut: () => void
  signingOut?: boolean
  children: ReactNode
}

/** Top bar (brand, user menu), sidebar navigation and the routed content. */
export function AppShell({ user, onSignOut, signingOut, children }: Props) {
  const [navOpen, setNavOpen] = useState(false)
  return (
    <div className="shell">
      <header className="shell__bar">
        <button
          type="button"
          className="shell__toggle"
          aria-label="Navigation"
          aria-expanded={navOpen}
          aria-controls="app-sidebar"
          onClick={() => setNavOpen((value) => !value)}
        >
          ☰
        </button>
        <h1 className="shell__brand">Shipir</h1>
        <UserMenu user={user} onSignOut={onSignOut} signingOut={signingOut} />
      </header>
      <div className="shell__body">
        <Sidebar role={user.role} open={navOpen} onNavigate={() => setNavOpen(false)} />
        <main className="shell__content">{children}</main>
      </div>
    </div>
  )
}
