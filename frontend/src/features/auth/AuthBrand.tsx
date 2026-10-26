import { Ship } from 'lucide-react'
import { useTranslation } from 'react-i18next'

/** Brand mark above the centered auth cards. */
export function AuthBrand() {
  const { t } = useTranslation('auth')
  return (
    <div className="auth-brand">
      <span className="auth-brand__mark">
        <Ship size={20} strokeWidth={1.75} aria-hidden="true" />
      </span>
      <span className="auth-brand__name">{t('AuthBrand.shipir')}</span>
    </div>
  )
}
