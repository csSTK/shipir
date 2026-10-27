import { Eye, X } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import './ViewContextChip.css'

/** 'Viewing: <name>' above the composer: tells the user what the next message carries as context, and lets them drop it. */
export function ViewContextChip({ label, onRemove }: { label: string; onRemove: () => void }) {
  const { t } = useTranslation('ai-chat')
  return (
    <p className="view-chip">
      <Eye size={16} strokeWidth={1.75} aria-hidden="true" />
      <span className="view-chip__text">{t('ViewContext.viewing', { name: label })}</span>
      <button type="button" className="view-chip__remove" aria-label={t('ViewContext.remove', { name: label })} onClick={onRemove}>
        <X size={16} strokeWidth={1.75} aria-hidden="true" />
      </button>
    </p>
  )
}
