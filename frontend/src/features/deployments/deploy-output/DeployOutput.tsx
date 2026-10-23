import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { Badge } from '../../../shared/ui/index.ts'
import { useRunStream, type RunResult } from './useRunStream.ts'
import './DeployOutput.css'

const BOTTOM_TOLERANCE_PX = 24

interface Props {
  title: string
  runId: string
  onFinished?: (result: RunResult) => void
}

/** Monospace live log of a deployment: follows new lines until the user scrolls up. */
export function DeployOutput({ title, runId, onFinished }: Props) {
  const { lines, result, connection } = useRunStream(runId, onFinished)
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
    const onScroll = () => {
      setFollow(el.scrollHeight - el.scrollTop - el.clientHeight <= BOTTOM_TOLERANCE_PX)
    }
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
    <section className="do" aria-label="Deployment output">
      <header className="do__head">
        <div className="do__title">
          <strong>{title}</strong>
          <span className="do__id">
            Run ID <code>{runId}</code>
          </span>
        </div>
        <div role="status" className="do__state">
          {result === 'DONE' && <Badge tone="ok">Succeeded</Badge>}
          {result === 'FAILED' && <Badge tone="bad">Failed</Badge>}
          {result === null && (
            <Badge tone={connection === 'reconnecting' ? 'warn' : 'muted'}>{connectionLabel}</Badge>
          )}
        </div>
      </header>
      <div className="do__body">
        <div ref={logRef} className="do__log" role="log" aria-label="Output lines" tabIndex={0}>
          {lines.length === 0 ? (
            <span className="do__empty">Waiting for output…</span>
          ) : (
            lines.map((l) => (
              <div key={l.seq} className="do__line">
                {l.text || ' '}
              </div>
            ))
          )}
        </div>
        {!following && (
          <button type="button" className="do__jump" onClick={jumpToLatest}>
            Jump to latest
          </button>
        )}
      </div>
    </section>
  )
}
