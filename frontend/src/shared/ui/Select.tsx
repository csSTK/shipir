import { ChevronDown } from 'lucide-react'
import { forwardRef, type ReactNode, type SelectHTMLAttributes } from 'react'
import { FormField } from './FormField.tsx'
import './FormField.css'
import './Select.css'

export interface SelectProps extends Omit<SelectHTMLAttributes<HTMLSelectElement>, 'id'> {
  label: string
  help?: ReactNode
  error?: string | null
  /** Visually hide the label (it stays the accessible name), e.g. inside table rows. */
  hideLabel?: boolean
}

/** Native select (keyboard and mobile behavior for free) with the shared field styling. */
export const Select = forwardRef<HTMLSelectElement, SelectProps>(function Select(
  { label, help, error, hideLabel = false, className, children, ...rest },
  ref,
) {
  return (
    <div className={hideLabel ? 'ui-field--bare' : undefined}>
      <FormField label={label} help={help} error={error}>
        {(control) => (
          <span className="ui-select">
            <select ref={ref} className={['ui-control', 'ui-select__input', className].filter(Boolean).join(' ')} {...control} {...rest}>
              {children}
            </select>
            <ChevronDown size={16} strokeWidth={1.75} aria-hidden="true" className="ui-select__chevron" />
          </span>
        )}
      </FormField>
    </div>
  )
})
