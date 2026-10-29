import { useState, type ReactNode } from 'react'
import { Download } from 'lucide-react'
import { Checkbox, Button, Toolbar } from '../../../shared/ui/index.ts'
import { downloadLog, LogDownloadError } from './logDownload.ts'
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
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [errorFor, setErrorFor] = useState<string | null>(null)

  async function download(href: string) {
    setBusy(true)
    setError(null)
    setErrorFor(href)
    try {
      await downloadLog(href)
    } catch (e) {
      setError(e instanceof LogDownloadError && e.status === 429 ? tr('LogOptions.tooManyDownloads') : tr('LogOptions.downloadFailed'))
    } finally {
      setBusy(false)
    }
  }
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
        <Checkbox className="logv__check" label={tr('LogOptions.timestamps')} checked={timestamps} onChange={(e) => onTimestamps(e.target.checked)} />
        {children}
        {downloadHref ? (
          <Button size="sm" className="logv__download" disabled={busy} onClick={() => void download(downloadHref)}>
            <Download size={16} strokeWidth={1.75} aria-hidden="true" /> {tr('LogOptions.download')}
          </Button>
        ) : null}
      </Toolbar>
      <p className="logv__note">{tr('LogOptions.downloadsAreCappedInSize')}</p>
      {error && errorFor === downloadHref && (
        <p role="alert" className="ui-error">
          {error}
        </p>
      )}
    </>
  )
}
