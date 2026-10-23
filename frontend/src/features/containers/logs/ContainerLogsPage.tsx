import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { Badge } from '../../../shared/ui/index.ts'
import { useContainers } from '../api.ts'
import { useContainerLogs, type LogEnd, type TailOption } from './useContainerLogs.ts'
import './ContainerLogsPage.css'

const BOTTOM_TOLERANCE_PX = 24
const TAILS: { value: TailOption; label: string }[] = [
  { value: '100', label: '100 lines' },
  { value: '200', label: '200 lines' },
  { value: '500', label: '500 lines' },
  { value: '1000', label: '1000 lines' },
  { value: 'all', label: 'All' },
]

const ERROR_TEXT: Record<string, string> = {
  NOT_FOUND: 'This container no longer exists.',
  UNAVAILABLE: 'The environment is not reachable.',
}

function endText(end: LogEnd): string {
  if (end.status === 'ENDED') return 'The log stream ended. The container stopped or was removed.'
  return ERROR_TEXT[end.code] ?? `The log stream failed (${end.code}).`
}

/** `/environments/:id/containers/:cid/logs`: live, read-only log of one container. */
export function ContainerLogsPage() {
  const { id: hostId = '', cid = '' } = useParams()
  const containers = useContainers(hostId)
  const name = containers.data?.find((c) => c.id === cid)?.name ?? cid.slice(0, 12)
  const [tail, setTail] = useState<TailOption>('200')
  const [timestamps, setTimestamps] = useState(false)
  const { lines, end, connection } = useContainerLogs(hostId, cid, tail, timestamps)

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
  }, [lines.length])

  useEffect(() => {
    const el = logRef.current
    if (!el) return
    const onScroll = () => setFollow(el.scrollHeight - el.scrollTop - el.clientHeight <= BOTTOM_TOLERANCE_PX)
    el.addEventListener('scroll', onScroll, { passive: true })
    return () => el.removeEventListener('scroll', onScroll)
  }, [])

  const jumpToLatest = () => {
    const el = logRef.current
    if (el) el.scrollTop = el.scrollHeight
    setFollow(true)
  }

  const connectionLabel =
    connection === 'connected' ? 'Live' : connection === 'connecting' ? 'Connecting…' : 'Connection lost, reconnecting…'

  return (
    <section className="clogs">
      <Link to={`/environments/${hostId}/containers`} className="clogs__back">
        ← Containers
      </Link>
      <h2 className="clogs__title">Logs: {name}</h2>
      <div className="clogs__bar">
        <label className="clogs__field">
          <span>Show last</span>
          <select value={tail} onChange={(e) => setTail(e.target.value as TailOption)} className="clogs__select">
            {TAILS.map((t) => (
              <option key={t.value} value={t.value}>
                {t.label}
              </option>
            ))}
          </select>
        </label>
        <label className="clogs__check">
          <input type="checkbox" checked={timestamps} onChange={(e) => setTimestamps(e.target.checked)} />
          <span>Timestamps</span>
        </label>
        <div role="status" className="clogs__state">
          {end === null && <Badge tone={connection === 'reconnecting' ? 'warn' : 'muted'}>{connectionLabel}</Badge>}
          {end?.status === 'ENDED' && <Badge tone="muted">Ended</Badge>}
          {end?.status === 'ERROR' && <Badge tone="bad">Error</Badge>}
        </div>
      </div>
      {end && (
        <p role={end.status === 'ERROR' ? 'alert' : undefined} className={end.status === 'ERROR' ? 'sk-error' : 'clogs__note'}>
          {endText(end)}
        </p>
      )}
      <div className="clogs__body">
        <div ref={logRef} className="clogs__log" role="log" aria-label="Container log" tabIndex={0}>
          {lines.length === 0 ? (
            <span className="clogs__empty">{end ? 'No log output.' : 'Waiting for output…'}</span>
          ) : (
            lines.map((l) => (
              <div key={l.seq} className={`clogs__line${l.stream === 'STDERR' ? ' clogs__line--err' : ''}`}>
                {l.timestamp && <time className="clogs__time">{l.timestamp}</time>}
                {l.text || ' '}
              </div>
            ))
          )}
        </div>
        {!following && (
          <button type="button" className="clogs__jump" onClick={jumpToLatest}>
            Jump to latest
          </button>
        )}
      </div>
    </section>
  )
}
