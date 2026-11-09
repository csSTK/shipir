import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { ArrowLeft } from 'lucide-react'
import { Badge, PageHeader } from '../../../shared/ui/index.ts'
import { useContainers } from '../api.ts'
import { LogOptions } from './LogOptions.tsx'
import { logDownloadHref } from './logDownload.ts'
import { LogView } from './LogView.tsx'
import { useContainerLogs, type LogEnd, type TailOption } from './useContainerLogs.ts'
import { useTranslation } from 'react-i18next'
import i18n from 'i18next'
import './ContainerLogsPage.css'

const TAILS: readonly TailOption[] = ['100', '200', '500', '1000', 'all']

const ERROR_TEXT: Record<string, string> = {
  get NOT_FOUND() { return i18n.t('containers:ContainerLogsPage.thisContainerNoLongerExists') },
  get UNAVAILABLE() { return i18n.t('containers:ContainerLogsPage.theEnvironmentIsNotReachable') },
}

function endText(end: LogEnd): string {
  if (end.status === 'ENDED') return i18n.t('containers:ContainerLogsPage.theLogStreamEndedThe')
  return ERROR_TEXT[end.code] ?? i18n.t('containers:ContainerLogsPage.theLogStreamFailedCode', { code: end.code })
}

/** `/environments/:id/containers/:cid/logs`: live, read-only log of one container. */
export function ContainerLogsPage() {
  const { t } = useTranslation('containers')
  const { id: hostId = '', cid = '' } = useParams()
  const containers = useContainers(hostId)
  const name = containers.data?.find((c) => c.id === cid)?.name ?? cid.slice(0, 12)
  const [tail, setTail] = useState<TailOption>('200')
  const [timestamps, setTimestamps] = useState(false)
  const { lines, end, connection, truncated, refused } = useContainerLogs(hostId, cid, tail, timestamps)

  const connectionLabel =
    connection === 'connected' ? t('ContainerLogsPage.live') : connection === 'connecting' ? t('ContainerLogsPage.connecting') : t('ContainerLogsPage.connectionLostReconnecting')

  return (
    <section className="ui-page clogs">
      <Link to={`/environments/${hostId}/containers`} className="clogs__back">
        <ArrowLeft size={16} strokeWidth={1.75} aria-hidden="true" /> {t('ContainerLogsPage.containers')}
      </Link>
      <PageHeader title={t('ContainerLogsPage.logsName', { name })} />
      <LogOptions
        tails={TAILS}
        tail={tail}
        onTail={setTail}
        timestamps={timestamps}
        onTimestamps={setTimestamps}
        downloadHref={logDownloadHref(hostId, cid, tail, timestamps)}
      >
        <div role="status" className="clogs__state">
          {end === null && !refused && <Badge tone={connection === 'reconnecting' ? 'warn' : 'muted'}>{connectionLabel}</Badge>}
          {end?.status === 'ENDED' && <Badge tone="muted">{t('ContainerLogsPage.ended')}</Badge>}
          {end?.status === 'ERROR' && <Badge tone="bad">{t('ContainerLogsPage.error')}</Badge>}
          {refused && <Badge tone="bad">{t('ContainerLogsPage.unavailable')}</Badge>}
        </div>
      </LogOptions>
      {refused && (
        <p role="alert" className="ui-error">
          {t('ContainerLogsPage.youAreNotAllowedTo')}
        </p>
      )}
      {end && (
        <p role={end.status === 'ERROR' ? 'alert' : undefined} className={end.status === 'ERROR' ? 'ui-error' : 'clogs__note'}>
          {endText(end)}
        </p>
      )}
      <LogView lines={lines} label={t('ContainerLogsPage.containerLog')} emptyText={end ? t('ContainerLogsPage.noLogOutput') : t('ContainerLogsPage.waitingForOutput')} truncated={truncated} />
    </section>
  )
}
