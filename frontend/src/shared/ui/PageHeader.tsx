import type { ReactNode } from 'react'
import './PageHeader.css'

export function PageHeader({ title, subtitle, actions }: { title: string; subtitle?: ReactNode; actions?: ReactNode }) {
  return (
    <header className="ui-pageheader">
      <div className="ui-pageheader__text">
        <h1 className="ui-pageheader__title">{title}</h1>
        {subtitle && <p className="ui-pageheader__subtitle">{subtitle}</p>}
      </div>
      {actions && <div className="ui-pageheader__actions">{actions}</div>}
    </header>
  )
}

/** Row for search, filters and bulk actions above a list. */
export function Toolbar({ children, label }: { children: ReactNode; label?: string }) {
  return (
    <div className="ui-toolbar" role="toolbar" aria-label={label}>
      {children}
    </div>
  )
}
