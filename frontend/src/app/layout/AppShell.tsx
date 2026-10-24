import { useEffect, useState, type ReactNode } from 'react'
import type { CurrentUser } from '../../features/auth/index.ts'
import { CommandPalette } from './CommandPalette.tsx'
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
  const [paletteOpen, setPaletteOpen] = useState(false)
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && !e.altKey && e.key.toLowerCase() === 'k') {
        e.preventDefault()
        setPaletteOpen((open) => !open)
      }
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [])
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
        <button type="button" className="shell__search" onClick={() => setPaletteOpen(true)}>
          Search
          <kbd aria-hidden="true">{/Mac|iPhone|iPad/.test(navigator.platform) ? '⌘K' : 'Ctrl K'}</kbd>
        </button>
        <UserMenu user={user} onSignOut={onSignOut} signingOut={signingOut} />
      </header>
      <div className="shell__body">
        <Sidebar role={user.role} open={navOpen} onNavigate={() => setNavOpen(false)} />
        <main className="shell__content">{children}</main>
      </div>
      <CommandPalette role={user.role} open={paletteOpen} onClose={() => setPaletteOpen(false)} />
    </div>
  )
}
