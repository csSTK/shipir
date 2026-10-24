import type { ReactNode } from 'react'
import { PageHeader } from '../../shared/ui/index.ts'
import './SettingsPage.css'

/** Shared layout of every settings page: header with title, subtitle and actions, then content at a readable width. */
export function SettingsPage({
  title,
  subtitle,
  actions,
  wide = false,
  children,
}: {
  title: string
  subtitle?: ReactNode
  actions?: ReactNode
  /** Tables use the full content width, forms stay narrow. */
  wide?: boolean
  children: ReactNode
}) {
  return (
    <section className={wide ? 'settings settings--wide' : 'settings'}>
      <PageHeader title={title} subtitle={subtitle} actions={actions} />
      {children}
    </section>
  )
}
