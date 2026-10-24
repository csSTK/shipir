import type { ReactNode } from 'react'
import './Badge.css'

export type BadgeTone = 'ok' | 'warn' | 'bad' | 'info' | 'accent' | 'muted'

/** Soft tinted badge; the dot plus text keep state from being color-only. */
export function Badge({ tone = 'muted', dot = true, children }: { tone?: BadgeTone; dot?: boolean; children: ReactNode }) {
  return (
    <span className={`ui-badge ui-badge--${tone}`}>
      {dot && <StatusDot tone={tone} />}
      {children}
    </span>
  )
}

/** Decorative status dot; pair with visible text or a label. */
export function StatusDot({ tone = 'muted' }: { tone?: BadgeTone }) {
  return <span className={`ui-dot ui-dot--${tone}`} aria-hidden="true" />
}
