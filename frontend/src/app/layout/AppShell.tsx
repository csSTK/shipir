import { Menu as MenuIcon, Search, Ship } from 'lucide-react'
import { AnimatePresence, motion, useReducedMotion } from 'motion/react'
import { useEffect, useState, type ReactNode } from 'react'
import { Link, useLocation } from 'react-router-dom'
import { AssistantDock } from '../../features/ai-chat/AssistantDock.tsx'
import { ChatDockProvider } from '../../features/ai-chat/ChatDockProvider.tsx'
import type { CurrentUser } from '../../features/auth/index.ts'
import { IconButton, springSmooth } from '../../shared/ui/index.ts'
import { CommandPalette } from './CommandPalette.tsx'
import { ErrorBoundary } from './ErrorBoundary.tsx'
import { EnvironmentSwitcher } from './EnvironmentSwitcher.tsx'
import { Sidebar } from './Sidebar.tsx'
import { UserMenu } from './UserMenu.tsx'
import { useMediaQuery } from './useMediaQuery.ts'
import { useTranslation } from 'react-i18next'
import './AppShell.css'
import '../../shared/ui/Page.css'

interface Props {
  user: CurrentUser
  onSignOut: () => void
  signingOut?: boolean
  children: ReactNode
}

/** Translucent top bar (brand, environment switcher, search, user menu), sidebar (drawer on narrow screens) and the routed content. */
export function AppShell({ user, onSignOut, signingOut, children }: Props) {
  const { t } = useTranslation('app')
  const [paletteOpen, setPaletteOpen] = useState(false)
  const narrow = useMediaQuery('(max-width: 720px)')
  const reduced = useReducedMotion()
  const { pathname } = useLocation()
  // The drawer is open for one location only, so navigating (or leaving the narrow layout) closes it.
  const [openAt, setOpenAt] = useState<string | null>(null)
  const navOpen = narrow && openAt === pathname
  const setNavOpen = (open: boolean | ((value: boolean) => boolean)) =>
    setOpenAt((open instanceof Function ? open(navOpen) : open) ? pathname : null)
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && !e.altKey && e.key.toLowerCase() === 'k') {
        e.preventDefault()
        setPaletteOpen((open) => !open)
      } else if (e.key === 'Escape') {
        setOpenAt(null)
      }
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [])
  const drawerOpen = navOpen
  return (
    <ChatDockProvider>
      <div className="shell">
        <header className="shell__bar">
          {narrow && (
            <IconButton
              icon={MenuIcon}
              aria-label={t('AppShell.navigation')}
              aria-expanded={navOpen}
              aria-controls="app-sidebar"
              onClick={() => setNavOpen((value) => !value)}
            />
          )}
          <Link to="/" className="shell__brand">
            <Ship size={20} strokeWidth={1.75} aria-hidden="true" />
            {t('AppShell.shipir')}
          </Link>
          <EnvironmentSwitcher />
          <span className="shell__spacer" />
          <button type="button" className="shell__search" aria-label={t('AppShell.search')} onClick={() => setPaletteOpen(true)}>
            <Search size={16} strokeWidth={1.75} aria-hidden="true" />
            <span className="shell__search-label">{t('AppShell.search2')}</span>
            <kbd aria-hidden="true">{/Mac|iPhone|iPad/.test(navigator.platform) ? t('AppShell.k') : t('AppShell.ctrlK')}</kbd>
          </button>
          <UserMenu user={user} onSignOut={onSignOut} signingOut={signingOut} />
        </header>
        <div className="shell__body">
          {!narrow && <Sidebar role={user.role} onNavigate={() => setNavOpen(false)} />}
          <main className="shell__content">
            <div className="shell__page">
              <ErrorBoundary resetKey={pathname}>{children}</ErrorBoundary>
            </div>
          </main>
        </div>
        <AnimatePresence>
          {drawerOpen && (
            <div className="shell__drawer-layer">
              <motion.div
                className="shell__scrim"
                initial={{ opacity: 0 }}
                animate={{ opacity: 1 }}
                exit={{ opacity: 0 }}
                transition={{ duration: 0.12 }}
                onClick={() => setNavOpen(false)}
              />
              <motion.div
                className="shell__drawer"
                initial={reduced ? { opacity: 0 } : { x: '-100%' }}
                animate={reduced ? { opacity: 1 } : { x: 0 }}
                exit={reduced ? { opacity: 0 } : { x: '-100%' }}
                transition={reduced ? { duration: 0.12 } : springSmooth}
              >
                <Sidebar role={user.role} drawer onNavigate={() => setNavOpen(false)} />
              </motion.div>
            </div>
          )}
        </AnimatePresence>
        <AssistantDock isAdmin={user.role === 'ADMIN'} narrow={narrow} />
        <CommandPalette role={user.role} open={paletteOpen} onClose={() => setPaletteOpen(false)} />
      </div>
    </ChatDockProvider>
  )
}
