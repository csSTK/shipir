import type { ReactNode } from 'react'
import { Download } from 'lucide-react'
import { Toolbar } from '../../../shared/ui/index.ts'
import type { TailOption } from './logStream.ts'
import { useTranslation } from 'react-i18next'
import i18n from 'i18next'

const LABELS: Record<TailOption, string> = { '100': '100 lines', '200': '200 lines', '500': '500 lines', '1000': '1000 lines', get all() { return i18n.t('containers:LogOptions.all') } }

interface LogOptionsProps<T extends TailOption> {
  tails: readonly T[]
  tail: T
  onTail: (tail: T) => void
  timestamps: boolean
  onTimestamps: (timestamps: boolean) => void
  /** Download endpoint for the current selection; null while there is nothing to download. */
  downloadHref: string | null
  /** Extra controls (container select, connection state). */
  children?: ReactNode
}

/** Tail, timestamps and Download: one row for the single-container page and the deployment panel. */
export function LogOptions<T extends TailOption>({ tails, tail, onTail, timestamps, onTimestamps, downloadHref, children }: LogOptionsProps<T>) {
  const { t: tr } = useTranslation('containers')
  return (
    <>
      <Toolbar label={tr('LogOptions.logOptions')}>
        <label className="logv__field">
          <span>{tr('LogOptions.showLast')}</span>
          <select value={tail} onChange={(e) => onTail(e.target.value as T)} className="logv__select">
            {tails.map((t) => (
              <option key={t} value={t}>
                {LABELS[t]}
              </option>
            ))}
          </select>
        </label>
        <label className="logv__check">
          <input type="checkbox" checked={timestamps} onChange={(e) => onTimestamps(e.target.checked)} />
          <span>{tr('LogOptions.timestamps')}</span>
        </label>
        {children}
        {downloadHref ? (
          <a className="ui-btn ui-btn--secondary ui-btn--sm logv__download" href={downloadHref} download>
            <Download size={16} strokeWidth={1.75} aria-hidden="true" /> {tr('LogOptions.download')}
          </a>
        ) : null}
      </Toolbar>
      <p className="logv__note">{tr('LogOptions.downloadsAreCappedInSize')}</p>
    </>
  )
}
