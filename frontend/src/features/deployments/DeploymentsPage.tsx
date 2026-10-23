import { useMemo, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { describeResourceError } from '../hosts/format.ts'
import { useCurrentUser } from '../auth/index.ts'
import { Badge, ResourceTable, type ResourceColumn } from '../../shared/ui/index.ts'
import {
  useDeployStack,
  useManagedStacks,
  useStacks,
  useStopStack,
  type ManagedStack,
  type StackStatus,
  type StackSummary,
} from './api.ts'
import { AddStackDialog } from './AddStackDialog.tsx'
import { describeStackError } from './stackErrors.ts'
import './StackUi.css'
import './StacksPage.css'

const STATUS: Record<StackStatus, { label: string; tone: 'ok' | 'warn' | 'muted' }> = {
  RUNNING: { label: 'Running', tone: 'ok' },
  PARTIAL: { label: 'Partial', tone: 'warn' },
  STOPPED: { label: 'Stopped', tone: 'muted' },
}

/** A live Compose project, a Shipir-managed stack, or both (joined by name or `managedStackId`). */
interface Row {
  name: string
  live: StackSummary | null
  managed: ManagedStack | null
}

function joinRows(live: StackSummary[], managed: ManagedStack[]): Row[] {
  const byId = new Map(managed.map((m) => [m.id, m]))
  const byName = new Map(managed.map((m) => [m.name, m]))
  const used = new Set<string>()
  const rows: Row[] = live.map((s) => {
    const m = (s.managedStackId ? byId.get(s.managedStackId) : undefined) ?? byName.get(s.name) ?? null
    if (m) used.add(m.id)
    return { name: s.name, live: s, managed: m }
  })
  // Managed stacks never deployed have no containers, so the live list does not show them.
  for (const m of managed) if (!used.has(m.id)) rows.push({ name: m.name, live: null, managed: m })
  return rows
}

/** `/environments/:id/stacks`: Compose projects of one environment, plus managed stacks. */
export function StacksPage() {
  const { id = '' } = useParams()
  const { data: user } = useCurrentUser()
  const canWrite = user?.role === 'ADMIN' || user?.role === 'OPERATOR'
  const live = useStacks(id)
  const managed = useManagedStacks(id)
  const deploy = useDeployStack(id)
  const stop = useStopStack(id)
  const [adding, setAdding] = useState(false)
  const [notice, setNotice] = useState<{ tone: 'ok' | 'error'; text: string } | null>(null)

  const rows = useMemo(
    () => (live.data ? joinRows(live.data, managed.data ?? []) : undefined),
    [live.data, managed.data],
  )

  function run(kind: 'Deploy' | 'Stop', stack: ManagedStack) {
    setNotice(null)
    if (kind === 'Deploy') {
      deploy.mutate(stack.id, {
        onSuccess: (d) => setNotice({ tone: 'ok', text: `Deployment of ${stack.name} started (ID ${d.deploymentId}).` }),
        onError: (e) => setNotice({ tone: 'error', text: describeStackError(e, `Could not deploy ${stack.name}.`) }),
      })
    } else {
      stop.mutate(stack.id, {
        onSuccess: () => setNotice({ tone: 'ok', text: `${stack.name} stopped.` }),
        onError: (e) => setNotice({ tone: 'error', text: describeStackError(e, `Could not stop ${stack.name}.`) }),
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
          <Link className="stacks__link" to={`/environments/${id}/stacks/${r.managed.id}`}>
            {r.name}
          </Link>
        ) : (
          <Link className="stacks__link" to={`/environments/${id}/containers?stack=${encodeURIComponent(r.name)}`}>
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
          <span className="stacks__actions">
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
    <section className="stacks">
      <div className="stacks__bar">
        <h2 className="stacks__title">Stacks</h2>
        {canWrite && (
          <button type="button" className="sk-btn sk-btn--primary" onClick={() => setAdding(true)}>
            Add stack
          </button>
        )}
      </div>
      {notice && (
        <p role={notice.tone === 'error' ? 'alert' : 'status'} className={notice.tone === 'error' ? 'sk-error' : 'stacks__notice'}>
          {notice.text}
        </p>
      )}
      <ResourceTable
        label="stacks"
        columns={columns}
        rows={rows}
        rowKey={(r) => r.managed?.id ?? r.name}
        searchText={(r) => `${r.name} ${r.live?.workingDir ?? ''}`}
        defaultSort="name"
        emptyMessage="No stacks in this environment."
        isPending={live.isPending}
        error={live.error ? describeResourceError(live.error) : null}
        onRetry={() => void live.refetch()}
      />
      {adding && <AddStackDialog hostId={id} onClose={() => setAdding(false)} />}
    </section>
  )
}
