import type { ReactNode } from 'react'
import { Download } from 'lucide-react'
import { Toolbar } from '../../../shared/ui/index.ts'
import type { TailOption } from './logStream.ts'

const LABELS: Record<TailOption, string> = { '100': '100 lines', '200': '200 lines', '500': '500 lines', '1000': '1000 lines', all: 'All' }

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
  return (
    <>
      <Toolbar label="Log options">
        <label className="logv__field">
          <span>Show last</span>
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
          <span>Timestamps</span>
        </label>
        {children}
        {downloadHref ? (
          <a className="ui-btn ui-btn--secondary ui-btn--sm logv__download" href={downloadHref} download>
            <Download size={16} strokeWidth={1.75} aria-hidden="true" /> Download
          </a>
        ) : null}
      </Toolbar>
      <p className="logv__note">Downloads are capped in size; if the limit is reached the file ends with a note.</p>
    </>
  )
}
