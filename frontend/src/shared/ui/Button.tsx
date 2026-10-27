import { cloneElement, forwardRef, isValidElement, type ButtonHTMLAttributes, type ReactElement, type ReactNode, type MouseEvent } from 'react'
import './Button.css'

export type ButtonVariant = 'primary' | 'secondary' | 'ghost' | 'ghost-danger' | 'danger'

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: ButtonVariant
  size?: 'md' | 'sm'
  /** Shows a spinner, sets aria-busy and disables the button. */
  loading?: boolean
  /** Renders the single child element (e.g. a router `Link` or `<a>`) with the button styling instead of a `<button>`. */
  asChild?: boolean
}

export const Button = forwardRef<HTMLButtonElement, ButtonProps>(function Button(
  { variant = 'secondary', size = 'md', loading = false, asChild = false, disabled, className, children, type = 'button', onClick, ...rest },
  ref,
) {
  const classes = ['ui-btn', `ui-btn--${variant}`, `ui-btn--${size}`, className].filter(Boolean).join(' ')
  if (asChild && isValidElement(children)) {
    const child = children as ReactElement<{ className?: string; children?: ReactNode }>
    return cloneElement(child, {
      ...rest,
      ref,
      className: [classes, child.props.className].filter(Boolean).join(' '),
      'aria-busy': loading || undefined,
      'aria-disabled': disabled || loading || undefined,
      onClick: disabled || loading ? (e: MouseEvent) => e.preventDefault() : onClick,
    } as object)
  }
  return (
    <button
      ref={ref}
      type={type}
      className={classes}
      disabled={disabled || loading}
      aria-busy={loading || undefined}
      onClick={loading ? undefined : onClick}
      {...rest}
    >
      {loading && <span className="ui-btn__spinner" aria-hidden="true" />}
      <span className="ui-btn__label">{children}</span>
    </button>
  )
})
