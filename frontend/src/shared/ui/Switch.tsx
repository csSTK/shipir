import { forwardRef, useId, type InputHTMLAttributes, type ReactNode } from 'react'
import './Switch.css'

export interface SwitchProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'id' | 'type' | 'role'> {
  label: string
  help?: ReactNode
}

/** On/off switch (checkbox with role="switch"): label on the left, track on the right, press feedback. */
export const Switch = forwardRef<HTMLInputElement, SwitchProps>(function Switch({ label, help, className, ...rest }, ref) {
  const id = useId()
  const helpId = help ? `${id}-help` : undefined
  return (
    <div className={['ui-switch', className].filter(Boolean).join(' ')}>
      <label className="ui-switch__row" htmlFor={id}>
        <span className="ui-switch__label">{label}</span>
        <input ref={ref} id={id} type="checkbox" role="switch" className="ui-switch__input" aria-describedby={helpId} {...rest} />
        <span className="ui-switch__track" aria-hidden="true">
          <span className="ui-switch__thumb" />
        </span>
      </label>
      {help && (
        <p id={helpId} className="ui-field__help">
          {help}
        </p>
      )}
    </div>
  )
})
