import type { LucideIcon } from 'lucide-react'
import type { ReactNode } from 'react'
import './EmptyState.css'

export function EmptyState({ icon: Icon, title, description, action }: { icon?: LucideIcon; title: string; description?: string; action?: ReactNode }) {
  return (
    <div className="ui-empty">
      {Icon && (
        <span className="ui-empty__icon">
          <Icon size={20} strokeWidth={1.75} aria-hidden="true" />
        </span>
      )}
      <p className="ui-empty__title">{title}</p>
      {description && <p className="ui-empty__desc">{description}</p>}
      {action && <div className="ui-empty__action">{action}</div>}
    </div>
  )
}
