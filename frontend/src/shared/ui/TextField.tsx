import { forwardRef, type InputHTMLAttributes, type ReactNode } from 'react'
import { FormField } from './FormField.tsx'
import './FormField.css'

export interface TextFieldProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'id'> {
  label: string
  help?: ReactNode
  error?: string | null
}

export const TextField = forwardRef<HTMLInputElement, TextFieldProps>(function TextField(
  { label, help, error, className, ...rest },
  ref,
) {
  return (
    <FormField label={label} help={help} error={error}>
      {(control) => <input ref={ref} className={['ui-control', className].filter(Boolean).join(' ')} {...control} {...rest} />}
    </FormField>
  )
})
