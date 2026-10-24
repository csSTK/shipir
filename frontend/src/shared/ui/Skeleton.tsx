import type { CSSProperties } from 'react'
import './Skeleton.css'

/** Decorative placeholder block; hidden from assistive tech (announce loading state elsewhere). */
export function Skeleton({ width, height, className }: { width?: CSSProperties['width']; height?: CSSProperties['height']; className?: string }) {
  return <span className={['ui-skeleton', className].filter(Boolean).join(' ')} style={{ width, height }} aria-hidden="true" />
}
