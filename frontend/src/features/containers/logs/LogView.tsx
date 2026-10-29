import { Fragment, useEffect, useLayoutEffect, useMemo, useRef, useState, type KeyboardEvent } from 'react'
import { ArrowDown, ChevronDown, ChevronUp } from 'lucide-react'
import { Checkbox, Button, IconButton, SearchField, Toolbar } from '../../../shared/ui/index.ts'
import type { LogLine } from './logStream.ts'
import { findMatches, type MatchRange } from './logSearch.ts'
import { useTranslation } from 'react-i18next'
import './LogView.css'

const BOTTOM_TOLERANCE_PX = 24
const SEARCH_DEBOUNCE_MS = 200
const SERIES_COUNT = 8

interface LogViewProps {
  lines: LogLine[]
  /** Accessible name of the log region. */
  label: string
  emptyText: string
  /** The oldest lines were dropped from the in-memory buffer. */
  truncated?: boolean
  /** Combined view: services in first-seen order (colour = position), shown as a legend that toggles them. */
  services?: string[]
  serviceEnds?: Record<string, 'ENDED' | 'ERROR'>
}

/** Search box with match navigation, filters and the log itself. Searching and filtering happen on the buffered lines only. */
export function LogView({ lines, label, emptyText, truncated = false, services, serviceEnds = {} }: LogViewProps) {
  const { t: tr } = useTranslation('containers')
  const [input, setInput] = useState('')
  const [query, setQuery] = useState('')
  const [stderrOnly, setStderrOnly] = useState(false)
  const [hidden, setHidden] = useState<ReadonlySet<string>>(new Set())
  const [active, setActive] = useState(0)

  useEffect(() => {
    const t = setTimeout(() => setQuery(input), SEARCH_DEBOUNCE_MS)
    return () => clearTimeout(t)
  }, [input])

  const visible = useMemo(
    () => lines.filter((l) => (!stderrOnly || l.stream === 'STDERR') && !(l.service && hidden.has(l.service))),
    [lines, stderrOnly, hidden],
  )

  // Matches per line plus the running index of each line's first match, so the active match maps to one <mark>.
  const { ranges, offsets, total } = useMemo(() => {
    const ranges = new Map<number, MatchRange[]>()
    const offsets = new Map<number, number>()
    let total = 0
    if (query) {
      for (const l of visible) {
        const r = findMatches(l.text, query)
        if (r.length) {
          ranges.set(l.seq, r)
          offsets.set(l.seq, total)
          total += r.length
        }
      }
    }
    return { ranges, offsets, total }
  }, [visible, query])

  const current = total === 0 ? 0 : Math.min(active, total - 1)

  const logRef = useRef<HTMLDivElement>(null)
  const [following, setFollowing] = useState(true)
  const followingRef = useRef(true)
  const setFollow = (value: boolean) => {
    followingRef.current = value
    setFollowing(value)
  }

  useLayoutEffect(() => {
    const el = logRef.current
    if (el && followingRef.current) el.scrollTop = el.scrollHeight
  }, [visible.length])

  useEffect(() => {
    const el = logRef.current
    if (!el) return
    const onScroll = () => setFollow(el.scrollHeight - el.scrollTop - el.clientHeight <= BOTTOM_TOLERANCE_PX)
    el.addEventListener('scroll', onScroll, { passive: true })
    return () => el.removeEventListener('scroll', onScroll)
  }, [])

  useEffect(() => {
    if (total === 0) return
    logRef.current?.querySelector('mark[data-active="true"]')?.scrollIntoView?.({ block: 'nearest' })
  }, [current, total, query])

  const step = (delta: number) => {
    if (total === 0) return
    setFollow(false)
    setActive((current + delta + total) % total)
  }
  const onSearchKey = (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Enter') {
      e.preventDefault()
      step(e.shiftKey ? -1 : 1)
    } else if (e.key === 'Escape' && input) {
      setInput('')
      setQuery('')
    }
  }

  const jumpToLatest = () => {
    const el = logRef.current
    if (el) el.scrollTop = el.scrollHeight
    setFollow(true)
  }
  const toggleService = (service: string) =>
    setHidden((prev) => {
      const next = new Set(prev)
      if (!next.delete(service)) next.add(service)
      return next
    })

  const filtered = stderrOnly || hidden.size > 0
  const clearFilters = () => {
    setStderrOnly(false)
    setHidden(new Set())
  }
  const colour = (service?: string) => (service && services ? (services.indexOf(service) % SERIES_COUNT) + 1 : 0)

  return (
    <div className="logv">
      <Toolbar label={tr('LogView.searchAndFilter')}>
        <SearchField
          aria-label={tr('LogView.searchLogs')}
          placeholder={tr('LogView.searchLogs')}
          value={input}
          onChange={(e) => {
            setInput(e.target.value)
            setActive(0)
            if (e.target.value) setFollow(false)
          }}
          onKeyDown={onSearchKey}
        />
        {query && (
          <>
            <span className="logv__count" aria-live="polite">
              {total === 0 ? tr('LogView.noMatches') : tr('LogView.valueOfTotal', { current: current + 1, total })}
            </span>
            <IconButton icon={ChevronUp} aria-label={tr('LogView.previousMatch')} onClick={() => step(-1)} disabled={total === 0} />
            <IconButton icon={ChevronDown} aria-label={tr('LogView.nextMatch')} onClick={() => step(1)} disabled={total === 0} />
          </>
        )}
        <Checkbox className="logv__check" label={tr('LogView.stderrOnly')} checked={stderrOnly} onChange={(e) => setStderrOnly(e.target.checked)} />
        {filtered && (
          <>
            <span className="logv__note">{tr('LogView.showingLengthOfLength2Lines', { shown: visible.length, total: lines.length })}</span>
            <Button variant="ghost" size="sm" onClick={clearFilters}>
              {tr('LogView.clearFilters')}
            </Button>
          </>
        )}
      </Toolbar>

      {services && services.length > 0 && (
        <ul className="logv__legend" aria-label={tr('LogView.services')}>
          {services.map((s) => (
            <li key={s}>
              <button type="button" className={`logv__chip logv__chip--${colour(s)}`} aria-pressed={!hidden.has(s)} onClick={() => toggleService(s)}>
                <span className="logv__dot" aria-hidden="true" />
                {s}
                {serviceEnds[s] && <span className="logv__ended">{serviceEnds[s] === 'ENDED' ? tr('LogView.ended') : tr('LogView.failed')}</span>}
              </button>
            </li>
          ))}
        </ul>
      )}

      {truncated && <p className="logv__note">{tr('LogView.theOldestLinesWereDropped')}</p>}

      <div className="logv__body">
        {/* aria-live off: a busy stream must not be announced line by line. */}
        <div ref={logRef} className="logv__log" role="log" aria-label={label} aria-live="off" tabIndex={0}>
          {visible.length === 0 ? (
            <span className="logv__empty">{lines.length > 0 ? tr('LogView.noLinesMatchTheFilters') : emptyText}</span>
          ) : (
            visible.map((l) => (
              <div key={l.seq} className={`logv__line${l.stream === 'STDERR' ? ' logv__line--err' : ''}`}>
                {l.service && <span className={`logv__svc logv__svc--${colour(l.service)}`}>{l.service}</span>}
                {l.timestamp && <time className="logv__time">{l.timestamp}</time>}
                <Highlighted text={l.text} ranges={ranges.get(l.seq)} offset={offsets.get(l.seq) ?? 0} active={current} />
              </div>
            ))
          )}
        </div>
        {!following && (
          <Button variant="primary" size="sm" className="logv__jump" onClick={jumpToLatest}>
            <ArrowDown size={16} strokeWidth={1.75} aria-hidden="true" /> {tr('LogView.jumpToLatest')}
          </Button>
        )}
      </div>
    </div>
  )
}

function Highlighted({ text, ranges, offset, active }: { text: string; ranges?: MatchRange[]; offset: number; active: number }) {
  if (!ranges) return <>{text || ' '}</>
  const parts: React.ReactNode[] = []
  let at = 0
  ranges.forEach((r, i) => {
    if (r.start > at) parts.push(text.slice(at, r.start))
    const isActive = offset + i === active
    parts.push(
      <mark key={i} className="logv__mark" data-active={isActive ? 'true' : undefined}>
        {text.slice(r.start, r.end)}
      </mark>,
    )
    at = r.end
  })
  if (at < text.length) parts.push(text.slice(at))
  return <Fragment>{parts}</Fragment>
}
