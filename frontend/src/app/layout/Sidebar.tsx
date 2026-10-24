import {
  Bell,
  Bot,
  Box,
  Clock,
  Disc3,
  GitBranch,
  HardDrive,
  KeyRound,
  Layers,
  LayoutDashboard,
  MessagesSquare,
  Network,
  ScrollText,
  Server,
  Sparkles,
  Users,
  Webhook,
  type LucideIcon,
} from 'lucide-react'
import { NavLink, useMatch } from 'react-router-dom'
import type { CurrentUser } from '../../features/auth/index.ts'
import { ENV_RESOURCES, useHostsOverview } from '../../features/hosts/index.ts'
import { useUnreadCount } from '../../features/notifications/index.ts'
import './Sidebar.css'

interface Props {
  role: CurrentUser['role']
  onNavigate: () => void
  /** Rendered inside the narrow-screen drawer instead of the fixed column. */
  drawer?: boolean
}

const RESOURCE_ICONS: Record<string, LucideIcon> = {
  deployments: Layers,
  containers: Box,
  images: Disc3,
  networks: Network,
  volumes: HardDrive,
}

const link = ({ isActive }: { isActive: boolean }) =>
  isActive ? 'sidebar__link sidebar__link--active' : 'sidebar__link'

function Item({
  to,
  icon: Icon,
  end,
  onNavigate,
  children,
}: {
  to: string
  icon: LucideIcon
  end?: boolean
  onNavigate: () => void
  children: React.ReactNode
}) {
  return (
    <NavLink to={to} end={end} className={link} onClick={onNavigate}>
      <Icon size={20} strokeWidth={1.75} aria-hidden="true" className="sidebar__icon" />
      <span className="sidebar__label">{children}</span>
    </NavLink>
  )
}

/** Resource links of the environment in the URL. Mounted only on environment routes. */
function EnvironmentSection({ envId, onNavigate }: { envId: string; onNavigate: () => void }) {
  const { data: envs } = useHostsOverview()
  const envName = envs?.find((e) => e.id === envId)?.name
  return (
    <div role="group" aria-labelledby="sidebar-env" className="sidebar__group">
      <span id="sidebar-env" className="sidebar__heading sidebar__heading--env">
        {envName ?? 'Environment'}
      </span>
      <Item to={`/environments/${envId}`} icon={LayoutDashboard} end onNavigate={onNavigate}>
        Dashboard
      </Item>
      {ENV_RESOURCES.map(({ slug, label }) => (
        <Item key={slug} to={`/environments/${envId}/${slug}`} icon={RESOURCE_ICONS[slug] ?? Box} onNavigate={onNavigate}>
          {label}
        </Item>
      ))}
    </div>
  )
}

/** Primary navigation. The Settings group is ADMIN-only. */
export function Sidebar({ role, onNavigate, drawer = false }: Props) {
  const unread = useUnreadCount().data?.count ?? 0
  const envId = useMatch('/environments/:id/*')?.params.id
  return (
    <nav id="app-sidebar" aria-label="Main" className={drawer ? 'sidebar sidebar--drawer' : 'sidebar'}>
      <Item to="/" icon={Server} end onNavigate={onNavigate}>
        Environments
      </Item>
      <Item to="/assistant" icon={Sparkles} onNavigate={onNavigate}>
        Assistant
      </Item>
      <NavLink to="/notifications" className={link} onClick={onNavigate}>
        <Bell size={20} strokeWidth={1.75} aria-hidden="true" className="sidebar__icon" />
        <span className="sidebar__label">Notifications</span>
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
          <Item to="/settings/users" icon={Users} onNavigate={onNavigate}>
            Users
          </Item>
          <Item to="/settings/notifications" icon={Webhook} onNavigate={onNavigate}>
            Notifications
          </Item>
          <Item to="/settings/retention" icon={Clock} onNavigate={onNavigate}>
            Retention
          </Item>
          <Item to="/settings/git" icon={GitBranch} onNavigate={onNavigate}>
            Git credentials
          </Item>
          <Item to="/settings/registries" icon={KeyRound} onNavigate={onNavigate}>
            Registries
          </Item>
          <Item to="/settings/ai" icon={Bot} end onNavigate={onNavigate}>
            AI assistant
          </Item>
          <Item to="/settings/ai/shared" icon={MessagesSquare} onNavigate={onNavigate}>
            Shared chats
          </Item>
          <Item to="/settings/audit-log" icon={ScrollText} onNavigate={onNavigate}>
            Audit log
          </Item>
        </div>
      )}
    </nav>
  )
}
