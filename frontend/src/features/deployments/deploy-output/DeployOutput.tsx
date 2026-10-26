import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { Badge } from '../../../shared/ui/index.ts'
import { useRunStream, type RunResult } from './useRunStream.ts'
import { useTranslation } from 'react-i18next'
import { Trans } from '../../../shared/i18n/Trans.tsx'
import './DeployOutput.css'

const BOTTOM_TOLERANCE_PX = 24

interface Props {
  title: string
  runId: string
  onFinished?: (result: RunResult) => void
}

/** Monospace live log of a deployment: follows new lines until the user scrolls up. */
export function DeployOutput({ title, runId, onFinished }: Props) {
  const { t } = useTranslation('deployments')
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
    connection === 'connected' ? t('DeployOutput.live') : connection === 'connecting' ? t('DeployOutput.connecting') : t('DeployOutput.connectionLostReconnecting')

  return (
    <section className="do" aria-label={t('DeployOutput.deploymentOutput')}>
      <header className="do__head">
        <div className="do__title">
          <Trans t={t} i18nKey="DeployOutput.titleRunIdRunid" values={{ title, runId }} components={{ strong: <strong />, span: <span className="do__id" />, code: <code /> }} />
        </div>
        <div role="status" className="do__state">
          {result === 'DONE' && <Badge tone="ok">{t('DeployOutput.succeeded')}</Badge>}
          {result === 'FAILED' && <Badge tone="bad">{t('DeployOutput.failed')}</Badge>}
          {result === null && (
            <Badge tone={connection === 'reconnecting' ? 'warn' : 'muted'}>{connectionLabel}</Badge>
          )}
        </div>
      </header>
      <div className="do__body">
        <div ref={logRef} className="do__log" role="log" aria-label={t('DeployOutput.outputLines')} tabIndex={0}>
          {lines.length === 0 ? (
            <span className="do__empty">{t('DeployOutput.waitingForOutput')}</span>
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
            {t('DeployOutput.jumpToLatest')}
          </button>
        )}
      </div>
    </section>
  )
}
