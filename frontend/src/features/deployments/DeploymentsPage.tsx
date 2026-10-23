import { useMemo, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { describeResourceError } from '../hosts/format.ts'
import { useCurrentUser } from '../auth/index.ts'
import { Badge, ResourceTable, type ResourceColumn } from '../../shared/ui/index.ts'
import {
  useDeployDeployment,
  useManagedDeployments,
  useDeployments,
  useStopDeployment,
  type ManagedDeployment,
  type DeploymentStatus,
  type DeploymentSummary,
} from './api.ts'
import { AddDeploymentDialog } from './AddDeploymentDialog.tsx'
import { describeDeploymentError } from './deploymentErrors.ts'
import './DeploymentUi.css'
import './DeploymentsPage.css'

const STATUS: Record<DeploymentStatus, { label: string; tone: 'ok' | 'warn' | 'muted' }> = {
  RUNNING: { label: 'Running', tone: 'ok' },
  PARTIAL: { label: 'Partial', tone: 'warn' },
  STOPPED: { label: 'Stopped', tone: 'muted' },
}

/** A live Compose project, a Shipir-managed deployment, or both (joined by name or `managedDeploymentId`). */
interface Row {
  name: string
  live: DeploymentSummary | null
  managed: ManagedDeployment | null
}

function joinRows(live: DeploymentSummary[], managed: ManagedDeployment[]): Row[] {
  const byId = new Map(managed.map((m) => [m.id, m]))
  const byName = new Map(managed.map((m) => [m.name, m]))
  const used = new Set<string>()
  const rows: Row[] = live.map((s) => {
    const m = (s.managedDeploymentId ? byId.get(s.managedDeploymentId) : undefined) ?? byName.get(s.name) ?? null
    if (m) used.add(m.id)
    return { name: s.name, live: s, managed: m }
  })
  // Managed deployments never deployed have no containers, so the live list does not show them.
  for (const m of managed) if (!used.has(m.id)) rows.push({ name: m.name, live: null, managed: m })
  return rows
}

/** `/environments/:id/deployments`: Compose projects of one environment, plus managed deployments. */
export function DeploymentsPage() {
  const { id = '' } = useParams()
  const { data: user } = useCurrentUser()
  const canWrite = user?.role === 'ADMIN' || user?.role === 'OPERATOR'
  const live = useDeployments(id)
  const managed = useManagedDeployments(id)
  const deploy = useDeployDeployment(id)
  const stop = useStopDeployment(id)
  const [adding, setAdding] = useState(false)
  const [notice, setNotice] = useState<{ tone: 'ok' | 'error'; text: string } | null>(null)

  const rows = useMemo(
    () => (live.data ? joinRows(live.data, managed.data ?? []) : undefined),
    [live.data, managed.data],
  )

  function run(kind: 'Deploy' | 'Stop', deployment: ManagedDeployment) {
    setNotice(null)
    if (kind === 'Deploy') {
      deploy.mutate(deployment.id, {
        onSuccess: (d) => setNotice({ tone: 'ok', text: `Deployment of ${deployment.name} started (ID ${d.runId}).` }),
        onError: (e) => setNotice({ tone: 'error', text: describeDeploymentError(e, `Could not deploy ${deployment.name}.`) }),
      })
    } else {
      stop.mutate(deployment.id, {
        onSuccess: () => setNotice({ tone: 'ok', text: `${deployment.name} stopped.` }),
        onError: (e) => setNotice({ tone: 'error', text: describeDeploymentError(e, `Could not stop ${deployment.name}.`) }),
      })
    }
  }

  const columns: ResourceColumn<Row>[] = [
    {
      id: 'name',
      header: 'Name',
      sortValue: (r) => r.name,
      render: (r) =>
        r.managed ? (
          <Link className="deployments__link" to={`/environments/${id}/deployments/${r.managed.id}`}>
            {r.name}
          </Link>
        ) : (
          <Link className="deployments__link" to={`/environments/${id}/containers?deployment=${encodeURIComponent(r.name)}`}>
            {r.name}
          </Link>
        ),
    },
    {
      id: 'status',
      header: 'Status',
      sortValue: (r) => r.live?.status ?? r.managed?.status ?? '',
      render: (r) => {
        if (r.live) return <Badge tone={STATUS[r.live.status].tone}>{STATUS[r.live.status].label}</Badge>
        return <Badge tone={r.managed?.status === 'ERROR' ? 'bad' : 'muted'}>{r.managed?.status === 'ERROR' ? 'Error' : 'Not deployed'}</Badge>
      },
    },
    {
      id: 'running',
      header: 'Running',
      sortValue: (r) => r.live?.runningCount ?? 0,
      render: (r) => (r.live ? `${r.live.runningCount} / ${r.live.containerCount}` : '–'),
    },
    { id: 'dir', header: 'Working directory', render: (r) => r.live?.workingDir ?? '–' },
    {
      id: 'managed',
      header: 'Managed',
      sortValue: (r) => (r.managed ? 0 : 1),
      render: (r) => (r.managed ? <Badge tone="ok">Managed</Badge> : '–'),
    },
  ]
  if (canWrite) {
    columns.push({
      id: 'actions',
      header: 'Actions',
      render: (r) =>
        r.managed ? (
          <span className="deployments__actions">
            <button type="button" className="sk-btn sk-btn--plain" aria-label={`Deploy ${r.name}`} onClick={() => run('Deploy', r.managed!)} disabled={deploy.isPending || stop.isPending}>
              Deploy
            </button>
            <button type="button" className="sk-btn sk-btn--plain" aria-label={`Stop ${r.name}`} onClick={() => run('Stop', r.managed!)} disabled={deploy.isPending || stop.isPending}>
              Stop
            </button>
          </span>
        ) : (
          '–'
        ),
    })
  }

  return (
    <section className="deployments">
      <div className="deployments__bar">
        <h2 className="deployments__title">Deployments</h2>
        {canWrite && (
          <button type="button" className="sk-btn sk-btn--primary" onClick={() => setAdding(true)}>
            Add deployment
          </button>
        )}
      </div>
      {notice && (
        <p role={notice.tone === 'error' ? 'alert' : 'status'} className={notice.tone === 'error' ? 'sk-error' : 'deployments__notice'}>
          {notice.text}
        </p>
      )}
      <ResourceTable
        label="deployments"
        columns={columns}
        rows={rows}
        rowKey={(r) => r.managed?.id ?? r.name}
        searchText={(r) => `${r.name} ${r.live?.workingDir ?? ''}`}
        defaultSort="name"
        emptyMessage="No deployments in this environment."
        isPending={live.isPending}
        error={live.error ? describeResourceError(live.error) : null}
        onRetry={() => void live.refetch()}
      />
      {adding && <AddDeploymentDialog hostId={id} onClose={() => setAdding(false)} />}
    </section>
  )
}
