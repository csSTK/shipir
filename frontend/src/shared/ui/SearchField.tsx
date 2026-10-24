import { forwardRef, type InputHTMLAttributes } from 'react'
import { Search } from 'lucide-react'
import './SearchField.css'

export interface SearchFieldProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'type' | 'aria-label'> {
  /** Required accessible name. */
  'aria-label': string
}

export const SearchField = forwardRef<HTMLInputElement, SearchFieldProps>(function SearchField({ className, ...rest }, ref) {
  return (
    <span className={['ui-search', className].filter(Boolean).join(' ')}>
      <Search size={16} strokeWidth={1.75} aria-hidden="true" className="ui-search__icon" />
      <input ref={ref} type="search" className="ui-search__input" {...rest} />
    </span>
  )
})
