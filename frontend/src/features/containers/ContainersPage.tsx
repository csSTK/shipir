import { useMemo, useState, type ReactNode } from 'react'
import { Link, useSearchParams, useParams } from 'react-router-dom'
import { useCurrentUser } from '../auth/index.ts'
import { useDeployments, type DeploymentSummary } from '../deployments/api.ts'
import { useImages, withTag, type Image } from '../images/api.ts'
import { deleteColumn } from '../hosts/deleteColumn.tsx'
import { DeleteResourceDialog } from '../hosts/DeleteResourceDialog.tsx'
import { describeResourceError, formatSize } from '../hosts/format.ts'
import { useContainerStats, type StatSample } from '../../shared/realtime/useContainerStats.ts'
import { Badge, ResourceTable, Sparkline, type ResourceColumn } from '../../shared/ui/index.ts'
import { ConfirmDialog } from '../deployments/ConfirmDialog.tsx'
import { actionsFor, describeActionError, useContainerAction, useContainers, useDeleteContainer, type Container, type ContainerAction } from './api.ts'
import './ContainersPage.css'

const dateFormat = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' })

function stateTone(state: string): 'ok' | 'warn' | 'bad' | 'muted' {
  if (state === 'running') return 'ok'
  if (state === 'restarting' || state === 'paused') return 'warn'
  if (state === 'dead') return 'bad'
  return 'muted'
}

function healthTone(health: string): 'ok' | 'warn' | 'bad' {
  return health === 'healthy' ? 'ok' : health === 'unhealthy' ? 'bad' : 'warn'
}

/** Containers have no flag of their own: the compose project's flag, or the flag of the image behind the tag. */
function updateTarget(hostId: string, c: Container, deployments: DeploymentSummary[], images: Image[]): string | null {
  const project = c.composeProject ? deployments.find((d) => d.name === c.composeProject && d.updateAvailable) : undefined
  if (project) {
    return project.managedDeploymentId ? `/environments/${hostId}/deployments/${project.managedDeploymentId}` : `/environments/${hostId}/deployments`
  }
  const ref = withTag(c.image)
  return images.some((i) => i.updateAvailable && i.repoTags.includes(ref)) ? `/environments/${hostId}/images` : null
}

/** `updates` maps container id to the page where its update is applied. */
function baseColumnsFor(updates: Map<string, string>): ResourceColumn<Container>[] {
  return [
  {
    id: 'name',
    header: 'Name',
    sortValue: (c) => c.name,
    render: (c) => (
      <Link className="containers__name" to={c.id} aria-label={`Details of ${c.name}`}>
        <strong>{c.name}</strong>
      </Link>
    ),
  },
  {
    id: 'state',
    header: 'State',
    sortValue: (c) => c.state,
    render: (c) => <Badge tone={stateTone(c.state)}>{c.state}</Badge>,
  },
  {
    id: 'health',
    header: 'Health',
    sortValue: (c) => c.health,
    render: (c) => (c.health ? <Badge tone={healthTone(c.health)}>{c.health}</Badge> : '–'),
  },
  {
    id: 'image',
    header: 'Image',
    sortValue: (c) => c.image,
    render: (c) => (
      <span className="containers__image">
        {c.image}
        {updates.has(c.id) && (
          <Link className="containers__update" to={updates.get(c.id)!} aria-label={`Update available for ${c.name}`}>
            <Badge tone="warn">Update available</Badge>
          </Link>
        )}
      </span>
    ),
  },
  { id: 'deployment', header: 'Deployment', sortValue: (c) => c.composeProject, render: (c) => c.composeProject ?? '–' },
  {
    id: 'ports',
    header: 'Ports',
    render: (c) =>
      c.ports.length ? (
        <ul className="containers__ports">
          {c.ports.map((p) => (
            <li key={p}>{p}</li>
          ))}
        </ul>
      ) : (
        '–'
      ),
  },
  {
    id: 'created',
    header: 'Created',
    sortValue: (c) => Date.parse(c.created),
    render: (c) => dateFormat.format(new Date(c.created)),
  },
  ]
}

const last = (samples: StatSample[] | undefined) => samples?.[samples.length - 1]

/** Live CPU and memory columns; only running containers have samples, everything else shows a dash. */
function statsColumns(series: Record<string, StatSample[]>): ResourceColumn<Container>[] {
  return [
    {
      id: 'cpu',
      header: 'CPU',
      sortValue: (c) => last(series[c.id])?.cpu ?? -1,
      render: (c) => {
        const samples = series[c.id]
        if (!samples) return '–'
        return (
          <span className="containers__stat">
            <span className="containers__stat-value">{last(samples)!.cpu.toFixed(1)}%</span>
            <Sparkline values={samples.map((s) => s.cpu)} max={100} label={`CPU history of ${c.name}`} />
          </span>
        )
      },
    },
    {
      id: 'memory',
      header: 'Memory',
      sortValue: (c) => last(series[c.id])?.memUsed ?? -1,
      render: (c) => {
        const samples = series[c.id]
        if (!samples) return '–'
        const now = last(samples)!
        return (
          <span className="containers__stat" title={now.memLimit > 0 ? `of ${formatSize(now.memLimit)}` : undefined}>
            <span className="containers__stat-value">{formatSize(now.memUsed)}</span>
            <Sparkline values={samples.map((s) => s.memUsed)} max={now.memLimit} label={`Memory history of ${c.name}`} />
          </span>
        )
      },
    },
  ]
}

/** Quick link to the live log, also reachable from the details page. */
const logsColumn: ResourceColumn<Container> = {
  id: 'logs',
  header: 'Logs',
  render: (c) => (
    <Link className="sk-btn sk-btn--plain containers__btn" to={`${c.id}/logs`} aria-label={`Logs of ${c.name}`}>
      Logs
    </Link>
  ),
}

/** ADMIN-only link to the interactive console; only running containers can have one. */
const consoleColumn: ResourceColumn<Container> = {
  id: 'console',
  header: 'Console',
  render: (c) =>
    c.state === 'running' ? (
      <Link className="sk-btn sk-btn--plain containers__btn" to={`${c.id}/console`} aria-label={`Console of ${c.name}`}>
        Console
      </Link>
    ) : (
      '–'
    ),
}

const LABELS: Record<ContainerAction, [label: string, pending: string]> = {
  start: ['Start', 'Starting…'],
  stop: ['Stop', 'Stopping…'],
  restart: ['Restart', 'Restarting…'],
  pause: ['Pause', 'Pausing…'],
  unpause: ['Unpause', 'Unpausing…'],
  kill: ['Kill', 'Killing…'],
}

/** State-aware lifecycle buttons for one row; the pressed one shows its pending label at once and the row is locked meanwhile. */
function actionColumn(pending: { id: string; action: ContainerAction } | null, onAction: (c: Container, a: ContainerAction) => void): ResourceColumn<Container> {
  return {
    id: 'lifecycle',
    header: 'Control',
    render: (c): ReactNode => {
      const busy = pending?.id === c.id
      return (
        <span className="containers__actions">
          {actionsFor(c.state).map((a) => (
            <button
              key={a}
              type="button"
              className={`sk-btn ${a === 'kill' ? 'sk-btn--danger-text' : 'sk-btn--plain'} containers__btn`}
              aria-label={`${LABELS[a][0]} container ${c.name}`}
              disabled={busy}
              onClick={() => onAction(c, a)}
            >
              {busy && pending.action === a ? LABELS[a][1] : LABELS[a][0]}
            </button>
          ))}
        </span>
      )
    },
  }
}

/** Looks up the managed deployment only once a delete is requested, so plain browsing costs no extra request. */
function DeleteContainerDialog({ hostId, container, onClose }: { hostId: string; container: Container; onClose: () => void }) {
  const deployments = useDeployments(hostId)
  const del = useDeleteContainer(hostId)
  const managed = deployments.data?.some((d) => d.name === container.composeProject && d.managedDeploymentId)
  return (
    <DeleteResourceDialog
      kind="container"
      name={container.name}
      forceOption={container.state === 'running'}
      warning={managed ? `Belongs to deployment ${container.composeProject} and may come back on the next deploy.` : null}
      onDelete={(force) => del.mutateAsync({ id: container.id, force })}
      onClose={onClose}
    />
  )
}

/** `/environments/:id/containers`: all containers of one environment, optionally `?deployment=<project>`. */
export function ContainersPage() {
  const { id = '' } = useParams()
  const [params, setParams] = useSearchParams()
  const deployment = params.get('deployment')
  const { data: user } = useCurrentUser()
  const canWrite = user?.role === 'ADMIN' || user?.role === 'OPERATOR'
  const { data, isPending, error, refetch } = useContainers(id)
  const [target, setTarget] = useState<Container | null>(null)
  const act = useContainerAction(id)
  const [killTarget, setKillTarget] = useState<Container | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)
  const pending = act.isPending ? act.variables : null

  function run(c: Container, action: ContainerAction) {
    setActionError(null)
    act.mutate(
      { id: c.id, action },
      {
        onError: (e) => setActionError(`${LABELS[action][0]} ${c.name}: ${describeActionError(e)}`),
        onSettled: () => setKillTarget(null),
      },
    )
  }
  const isAdmin = user?.role === 'ADMIN'
  const { series } = useContainerStats(id)
  const deployments = useDeployments(id)
  const images = useImages(id)
  const updates = useMemo(() => {
    const map = new Map<string, string>()
    const d = Array.isArray(deployments.data) ? deployments.data : []
    const im = Array.isArray(images.data) ? images.data : []
    for (const c of data ?? []) {
      const target = updateTarget(id, c, d, im)
      if (target) map.set(c.id, target)
    }
    return map
  }, [id, data, deployments.data, images.data])
  const baseColumns = useMemo(() => baseColumnsFor(updates), [updates])
  const withStats = [...baseColumns.slice(0, 4), ...statsColumns(series), ...baseColumns.slice(4)]
  const tableColumns = canWrite
    ? [...withStats, logsColumn, ...(isAdmin ? [consoleColumn] : []), actionColumn(pending, (c, a) => (a === 'kill' ? setKillTarget(c) : run(c, a))), deleteColumn<Container>('container', (c) => c.name, setTarget)]
    : [...withStats, logsColumn]

  const rows = deployment ? data?.filter((c) => c.composeProject === deployment) : data

  return (
    <section className="containers">
      <h2 className="containers__title">Containers</h2>
      {actionError && (
        <p role="alert" className="sk-error">
          {actionError}
        </p>
      )}
      <ResourceTable
        label="containers"
        columns={tableColumns}
        rows={rows}
        rowKey={(c) => c.id}
        searchText={(c) => `${c.name} ${c.image} ${c.composeProject ?? ''}`}
        defaultSort="name"
        emptyMessage={deployment ? `No containers in deployment ${deployment}.` : 'No containers in this environment.'}
        isPending={isPending}
        error={error ? describeResourceError(error) : null}
        onRetry={() => void refetch()}
        toolbar={
          deployment && (
            <button type="button" className="containers__chip" onClick={() => setParams({})}>
              Deployment: {deployment} <span aria-hidden="true">×</span>
            </button>
          )
        }
      />
      {killTarget && (
        <ConfirmDialog
          title="Kill container"
          confirmLabel={act.isPending ? 'Killing…' : 'Kill'}
          danger
          pending={act.isPending}
          onConfirm={() => run(killTarget, 'kill')}
          onClose={() => setKillTarget(null)}
        >
          Kill {killTarget.name} immediately? The container is stopped without a graceful shutdown and unsaved data may be lost.
        </ConfirmDialog>
      )}
      {target && <DeleteContainerDialog hostId={id} container={target} onClose={() => setTarget(null)} />}
    </section>
  )
}
