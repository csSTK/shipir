import { useId, type ReactNode } from 'react'
import './FormField.css'

export interface FieldControlProps {
  id: string
  'aria-describedby'?: string
  'aria-invalid'?: true
}

interface FormFieldProps {
  label: string
  help?: ReactNode
  /** Already-mapped message; marks the control invalid. */
  error?: string | null
  /** Receives the props that tie the control to its label, help and error. */
  /** Visually hide the label; it stays the accessible name. */
  hideLabel?: boolean
  children: (control: FieldControlProps) => ReactNode
}

/** Label, control, help text and inline error in one consistent block. */
export function FormField({ label, help, error, hideLabel, children }: FormFieldProps) {
  const id = useId()
  const helpId = help ? `${id}-help` : undefined
  const errorId = error ? `${id}-error` : undefined
  const describedBy = [helpId, errorId].filter(Boolean).join(' ') || undefined
  return (
    <div className="ui-field">
      <label className={hideLabel ? 'ui-field__label visually-hidden' : 'ui-field__label'} htmlFor={id}>
        {label}
      </label>
      {children({ id, 'aria-describedby': describedBy, 'aria-invalid': error ? true : undefined })}
      {help && (
        <p id={helpId} className="ui-field__help">
          {help}
        </p>
      )}
      {error && (
        <p id={errorId} role="alert" className="ui-field__error">
          {error}
        </p>
      )}
    </div>
  )
}
