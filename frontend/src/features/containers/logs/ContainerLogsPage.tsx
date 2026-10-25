import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { ArrowLeft } from 'lucide-react'
import { Badge, PageHeader } from '../../../shared/ui/index.ts'
import { useContainers } from '../api.ts'
import { LogOptions } from './LogOptions.tsx'
import { logDownloadHref } from './logDownload.ts'
import { LogView } from './LogView.tsx'
import { useContainerLogs, type LogEnd, type TailOption } from './useContainerLogs.ts'
import './ContainerLogsPage.css'

const TAILS: readonly TailOption[] = ['100', '200', '500', '1000', 'all']

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
  const { lines, end, connection, truncated, refused } = useContainerLogs(hostId, cid, tail, timestamps)

  const connectionLabel =
    connection === 'connected' ? 'Live' : connection === 'connecting' ? 'Connecting…' : 'Connection lost, reconnecting…'

  return (
    <section className="clogs">
      <Link to={`/environments/${hostId}/containers`} className="clogs__back">
        <ArrowLeft size={16} strokeWidth={1.75} aria-hidden="true" /> Containers
      </Link>
      <PageHeader title={`Logs: ${name}`} />
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
          {end?.status === 'ENDED' && <Badge tone="muted">Ended</Badge>}
          {end?.status === 'ERROR' && <Badge tone="bad">Error</Badge>}
          {refused && <Badge tone="bad">Unavailable</Badge>}
        </div>
      </LogOptions>
      {refused && (
        <p role="alert" className="ui-error">
          You are not allowed to follow this log, or the stream is not available.
        </p>
      )}
      {end && (
        <p role={end.status === 'ERROR' ? 'alert' : undefined} className={end.status === 'ERROR' ? 'ui-error' : 'clogs__note'}>
          {endText(end)}
        </p>
      )}
      <LogView lines={lines} label="Container log" emptyText={end ? 'No log output.' : 'Waiting for output…'} truncated={truncated} />
    </section>
  )
}
