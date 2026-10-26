import { LogOut, Settings } from 'lucide-react'
import { useNavigate } from 'react-router-dom'
import type { CurrentUser } from '../../features/auth/index.ts'
import { Menu } from '../../shared/ui/index.ts'
import { useTranslation } from 'react-i18next'
import './UserMenu.css'

interface Props {
  user: CurrentUser
  onSignOut: () => void
  signingOut?: boolean
}

/** Top bar user button with a menu (Settings, Sign out). Keyboard and focus handling come from `Menu`. */
export function UserMenu({ user, onSignOut, signingOut = false }: Props) {
  const { t } = useTranslation('app')
  const navigate = useNavigate()
  return (
    <Menu
      trigger={
        <button type="button" className="user-menu__trigger" aria-label={`${user.username} (${user.role})`}>
          <span className="user-menu__avatar" aria-hidden="true">
            {user.username.slice(0, 1).toUpperCase()}
          </span>
          <span className="user-menu__text">
            <span className="user-menu__name">{user.username}</span>
            <span className="user-menu__role">{user.role}</span>
          </span>
        </button>
      }
      items={[
        { label: t('UserMenu.settings'), icon: <Settings size={16} strokeWidth={1.75} />, onSelect: () => navigate('/account') },
        { label: t('UserMenu.signOut'), icon: <LogOut size={16} strokeWidth={1.75} />, disabled: signingOut, onSelect: onSignOut },
      ]}
    />
  )
}
