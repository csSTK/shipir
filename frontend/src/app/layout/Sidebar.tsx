import { NavLink, useMatch } from 'react-router-dom'
import type { CurrentUser } from '../../features/auth/index.ts'
import { ENV_RESOURCES, useHostsOverview } from '../../features/hosts/index.ts'
import { useUnreadCount } from '../../features/notifications/index.ts'
import './Sidebar.css'

interface Props {
  role: CurrentUser['role']
  open: boolean
  onNavigate: () => void
}

const link = ({ isActive }: { isActive: boolean }) =>
  isActive ? 'sidebar__link sidebar__link--active' : 'sidebar__link'

/** Resource links of the environment in the URL. Mounted only on environment routes. */
function EnvironmentSection({ envId, onNavigate }: { envId: string; onNavigate: () => void }) {
  const { data: envs } = useHostsOverview()
  const envName = envs?.find((e) => e.id === envId)?.name
  return (
    <div role="group" aria-labelledby="sidebar-env" className="sidebar__group">
      <span id="sidebar-env" className="sidebar__heading sidebar__heading--env">
        {envName ?? 'Environment'}
      </span>
      <NavLink to={`/environments/${envId}`} end className={link} onClick={onNavigate}>
        Dashboard
      </NavLink>
      {ENV_RESOURCES.map(({ slug, label }) => (
        <NavLink key={slug} to={`/environments/${envId}/${slug}`} className={link} onClick={onNavigate}>
          {label}
        </NavLink>
      ))}
    </div>
  )
}

/** Primary navigation. The Settings group is ADMIN-only; collapses on narrow screens. */
export function Sidebar({ role, open, onNavigate }: Props) {
  const unread = useUnreadCount().data?.count ?? 0
  const envId = useMatch('/environments/:id/*')?.params.id
  return (
    <nav
      id="app-sidebar"
      aria-label="Main"
      className={open ? 'sidebar sidebar--open' : 'sidebar'}
    >
      <NavLink to="/" end className={link} onClick={onNavigate}>
        Environments
      </NavLink>
      <NavLink to="/assistant" className={link} onClick={onNavigate}>
        Assistant
      </NavLink>
      <NavLink to="/notifications" className={link} onClick={onNavigate}>
        Notifications
        {unread > 0 && (
          <span className="sidebar__badge" aria-label={`${unread} unread`}>
            {unread > 99 ? '99+' : unread}
          </span>
        )}
      </NavLink>
      {envId && <EnvironmentSection envId={envId} onNavigate={onNavigate} />}
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
          <NavLink to="/settings/git" className={link} onClick={onNavigate}>
            Git credentials
          </NavLink>
          <NavLink to="/settings/ai" className={link} onClick={onNavigate}>
            AI assistant
          </NavLink>
          <NavLink to="/settings/audit-log" className={link} onClick={onNavigate}>
            Audit log
          </NavLink>
        </div>
      )}
    </nav>
  )
}
