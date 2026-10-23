import { NavLink } from 'react-router-dom'
import type { CurrentUser } from '../../features/auth/index.ts'
import './Sidebar.css'

interface Props {
  role: CurrentUser['role']
  open: boolean
  onNavigate: () => void
}

const link = ({ isActive }: { isActive: boolean }) =>
  isActive ? 'sidebar__link sidebar__link--active' : 'sidebar__link'

/** Primary navigation. The Settings group is ADMIN-only; collapses on narrow screens. */
export function Sidebar({ role, open, onNavigate }: Props) {
  return (
    <nav
      id="app-sidebar"
      aria-label="Main"
      className={open ? 'sidebar sidebar--open' : 'sidebar'}
    >
      <NavLink to="/" end className={link} onClick={onNavigate}>
        Environments
      </NavLink>
      <NavLink to="/notifications" className={link} onClick={onNavigate}>
        Notifications
      </NavLink>
      {role === 'ADMIN' && (
        <div role="group" aria-labelledby="sidebar-settings" className="sidebar__group">
          <span id="sidebar-settings" className="sidebar__heading">
            Settings
          </span>
          <NavLink to="/settings/notifications" className={link} onClick={onNavigate}>
            Notifications
          </NavLink>
          <NavLink to="/settings/retention" className={link} onClick={onNavigate}>
            Retention
          </NavLink>
          <NavLink to="/settings/audit-log" className={link} onClick={onNavigate}>
            Audit log
          </NavLink>
        </div>
      )}
    </nav>
  )
}
