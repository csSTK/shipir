import { useState } from 'react'
import { Badge, Button, Card } from '../../../shared/ui/index.ts'
import { useContainers } from '../../containers/api.ts'
import { LogOptions, LogView, logDownloadHref, useDeploymentLogs, type ProjectTailOption } from '../../containers/logs/index.ts'
import './DeploymentLogsPanel.css'

const TAILS: readonly ProjectTailOption[] = ['100', '500', '1000', 'all']

const NOTICE_TEXT: Record<string, string> = {
  CONTAINER_LIMIT: 'This deployment has too many containers to follow all of them; only the first ones are shown.',
  UNAVAILABLE: 'The environment is not reachable. Logs resume when it is back.',
  NOT_FOUND: 'No containers found for this deployment. Deploy it first.',
}

interface Props {
  hostId: string
  /** Compose project name = deployment name. */
  project: string
}

/** 'Logs' section of the deployment detail page: combined live log of all containers, off until the user asks for it. */
export function DeploymentLogsPanel({ hostId, project }: Props) {
  const [shown, setShown] = useState(false)
  return (
    <Card className="sd-panel dlogs">
      <div className="sd-panel__head">
        <h3 className="sd-panel__title">Logs</h3>
        <Button variant="secondary" size="sm" onClick={() => setShown((v) => !v)}>
          {shown ? 'Hide logs' : 'Show logs'}
        </Button>
      </div>
      {shown && <ProjectLogs hostId={hostId} project={project} />}
    </Card>
  )
}

function ProjectLogs({ hostId, project }: Props) {
  const [tail, setTail] = useState<ProjectTailOption>('100')
  const [timestamps, setTimestamps] = useState(false)
  const [chosen, setChosen] = useState<string | null>(null)
  const { lines, truncated, services, serviceEnds, notices, refused, connection } = useDeploymentLogs(hostId, project, tail, timestamps)

  const containers = useContainers(hostId)
  const options = (containers.data ?? []).filter((c) => c.composeProject === project)
  const downloadId = options.find((c) => c.id === chosen)?.id ?? options[0]?.id

  const connectionLabel =
    connection === 'connected' ? 'Live' : connection === 'connecting' ? 'Connecting…' : 'Connection lost, reconnecting…'

  return (
    <div className="dlogs__body">
      <LogOptions
        tails={TAILS}
        tail={tail}
        onTail={setTail}
        timestamps={timestamps}
        onTimestamps={setTimestamps}
        downloadHref={downloadId ? logDownloadHref(hostId, downloadId, tail, timestamps) : null}
      >
        {options.length > 0 && (
          <label className="logv__field">
            <span>Download container</span>
            <select value={downloadId} onChange={(e) => setChosen(e.target.value)} className="logv__select">
              {options.map((c) => (
                <option key={c.id} value={c.id}>
                  {c.composeService ?? c.name} ({c.name})
                </option>
              ))}
            </select>
          </label>
        )}
        {!refused && <Badge tone={connection === 'reconnecting' ? 'warn' : 'muted'}>{connectionLabel}</Badge>}
      </LogOptions>
      {refused && (
        <p role="alert" className="ui-error">
          You are not allowed to follow the logs of this deployment, or the stream is not available.
        </p>
      )}
      {notices.length > 0 && (
        <div role="status" aria-label="Log notices" className="dlogs__notices">
          {notices.map((code) => (
            <p key={code} className="dlogs__notice">
              {NOTICE_TEXT[code] ?? `Log stream problem (${code}).`}
            </p>
          ))}
        </div>
      )}
      <LogView
        lines={lines}
        label="Deployment log"
        emptyText="Waiting for output…"
        truncated={truncated}
        services={services}
        serviceEnds={serviceEnds}
      />
    </div>
  )
}
