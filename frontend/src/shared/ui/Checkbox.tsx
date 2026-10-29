import { forwardRef, type InputHTMLAttributes, type ReactNode } from 'react'
import './Checkbox.css'

export interface CheckboxProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'type' | 'role'> {
  label: ReactNode
}

/** Checkbox for list selections, confirm options and view filters (on/off settings use Switch): box left, label right, press feedback. */
export const Checkbox = forwardRef<HTMLInputElement, CheckboxProps>(function Checkbox({ label, className, ...rest }, ref) {
  return (
    <label className={['ui-checkbox', className].filter(Boolean).join(' ')}>
      <input ref={ref} type="checkbox" className="ui-checkbox__input" {...rest} />
      <span className="ui-checkbox__box" aria-hidden="true">
        <svg className="ui-checkbox__mark" viewBox="0 0 12 12" focusable="false">
          <path d="M2.5 6.5l2.5 2.5 4.5-5.5" fill="none" stroke="currentColor" strokeWidth="1.75" strokeLinecap="round" strokeLinejoin="round" />
        </svg>
      </span>
      <span className="ui-checkbox__label">{label}</span>
    </label>
  )
})
