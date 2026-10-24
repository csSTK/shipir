import { Ship } from 'lucide-react'

/** Brand mark above the centered auth cards. */
export function AuthBrand() {
  return (
    <div className="auth-brand">
      <span className="auth-brand__mark">
        <Ship size={20} strokeWidth={1.75} aria-hidden="true" />
      </span>
      <span className="auth-brand__name">Shipir</span>
    </div>
  )
}
