import { forwardRef, type InputHTMLAttributes, type ReactNode } from 'react'
import { FormField } from './FormField.tsx'
import './FormField.css'

export interface TextFieldProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'id'> {
  label: string
  help?: ReactNode
  error?: string | null
  /** Visually hide the label (it stays the accessible name), e.g. in repeated rows. */
  hideLabel?: boolean
}

export const TextField = forwardRef<HTMLInputElement, TextFieldProps>(function TextField(
  { label, help, error, hideLabel = false, className, ...rest },
  ref,
) {
  return (
    <div className={hideLabel ? 'ui-field--bare' : undefined}>
      <FormField label={label} help={help} error={error}>
        {(control) => <input ref={ref} className={['ui-control', className].filter(Boolean).join(' ')} {...control} {...rest} />}
      </FormField>
    </div>
  )
})
