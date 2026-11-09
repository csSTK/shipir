import { forwardRef, type ButtonHTMLAttributes } from 'react'
import type { LucideIcon } from 'lucide-react'
import { Tooltip } from './Tooltip.tsx'
import './IconButton.css'

export interface IconButtonProps extends Omit<ButtonHTMLAttributes<HTMLButtonElement>, 'aria-label' | 'children'> {
  /** Required accessible name; also shown as tooltip. */
  'aria-label': string
  icon: LucideIcon
  variant?: 'ghost' | 'secondary' | 'danger'
}

export const IconButton = forwardRef<HTMLButtonElement, IconButtonProps>(function IconButton(
  { icon: Icon, variant = 'ghost', className, type = 'button', ...rest },
  ref,
) {
  // Disabled uses aria-disabled (not the disabled attribute) so the button stays focusable and
  // hoverable: the tooltip (the reason) opens, and activation is suppressed.
  const { disabled, onClick, ...other } = rest
  return (
    <Tooltip content={rest['aria-label']}>
      <button
        ref={ref}
        type={type}
        className={['ui-iconbtn', `ui-iconbtn--${variant}`, className].filter(Boolean).join(' ')}
        {...other}
        aria-disabled={disabled ? true : undefined}
        onClick={disabled ? (e) => e.preventDefault() : onClick}
      >
        <Icon size={20} strokeWidth={1.75} aria-hidden="true" />
      </button>
    </Tooltip>
  )
})
