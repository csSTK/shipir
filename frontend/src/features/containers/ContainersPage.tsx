import { useMemo, useState, type ReactNode } from 'react'
import { Link, useNavigate, useSearchParams, useParams } from 'react-router-dom'
import { Info, Pause, Play, RotateCw, ScrollText, Square, SquareTerminal, Trash2, X, Zap } from 'lucide-react'
import { useCurrentUser } from '../auth/index.ts'
import { useDeployments, type DeploymentSummary } from '../deployments/api.ts'
import { useImages, withTag, type Image } from '../images/api.ts'
import { DeleteResourceDialog } from '../hosts/DeleteResourceDialog.tsx'
import { describeResourceError, formatSize } from '../hosts/format.ts'
import { useContainerStats, type StatSample } from '../../shared/realtime/useContainerStats.ts'
import { Badge, Button, PageHeader, ResourceTable, Sparkline, StatusDot, type MenuEntry, type ResourceColumn } from '../../shared/ui/index.ts'
import type { RowActions } from '../../shared/ui/ResourceTable.tsx'
import { useCanOpenConsole } from './exec/api.ts'
import { ConfirmDialog } from '../deployments/ConfirmDialog.tsx'
import { actionsFor, describeActionError, useContainerAction, useContainers, useDeleteContainer, type Container, type ContainerAction } from './api.ts'
import { CreateContainerDialog } from './CreateContainerDialog.tsx'
import { useTranslation } from 'react-i18next'
import i18n from 'i18next'
import { dateTimeFormat, formatNumber, ONE_DECIMAL } from '../../shared/i18n/format.ts'
import './ContainersPage.css'

const dateFormat = dateTimeFormat({ dateStyle: 'medium', timeStyle: 'short' })

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

const MAX_PORT_CHIPS = 2

/** `updates` maps container id to the page where its update is applied; `pending` is the row whose action is running. */
function baseColumnsFor(updates: Map<string, string>, pending: { id: string; action: ContainerAction } | null): ResourceColumn<Container>[] {
  return [
    {
      id: 'name',
      header: i18n.t('containers:ContainersPage.name'),
      defaultWidth: 210,
      sortValue: (c) => c.name,
      render: (c) => (
        <span className="containers__namecell">
          <Link className="containers__name" to={c.id} aria-label={i18n.t('containers:ContainersPage.detailsOfName', { name: c.name })}>
            {c.name}
          </Link>
          <span className="containers__image">
            <span className="containers__image-text">{c.image}</span>
            {updates.has(c.id) && (
              <Link className="containers__update" to={updates.get(c.id)!} aria-label={i18n.t('containers:ContainersPage.updateAvailableForName', { name: c.name })}>
                <Badge tone="warn">{i18n.t('containers:ContainersPage.updateAvailable')}</Badge>
              </Link>
            )}
          </span>
        </span>
      ),
    },
    {
      id: 'state',
      header: i18n.t('containers:ContainersPage.state'),
      defaultWidth: 130,
      sortValue: (c) => c.state,
      render: (c) => (
        <span className="containers__state">
          {pending?.id === c.id ? (
            <Badge tone="info" dot={false}>
              {LABELS[pending.action][1]}
            </Badge>
          ) : (
            <Badge tone={stateTone(c.state)}>{c.state}</Badge>
          )}
          {c.health && (
            <span className="containers__health">
              <StatusDot tone={healthTone(c.health)} />
              {c.health}
            </span>
          )}
        </span>
      ),
    },
    { id: 'deployment', header: i18n.t('containers:ContainersPage.deployment'), defaultWidth: 120, sortValue: (c) => c.composeProject, render: (c) => c.composeProject ?? '–' },
    {
      id: 'ports',
      header: i18n.t('containers:ContainersPage.ports'),
      defaultWidth: 130,
      render: (c) =>
        c.ports.length ? (
          <ul className="containers__ports" title={c.ports.join(', ')}>
            {c.ports.slice(0, MAX_PORT_CHIPS).map((p) => (
              <li key={p}>{p}</li>
            ))}
            {c.ports.length > MAX_PORT_CHIPS && <li className="containers__more">+{c.ports.length - MAX_PORT_CHIPS}</li>}
          </ul>
        ) : (
          '–'
        ),
    },
    {
      id: 'created',
      header: i18n.t('containers:ContainersPage.created'),
      defaultHidden: true,
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
      defaultWidth: 130,
      sortValue: (c) => last(series[c.id])?.cpu ?? -1,
      render: (c) => {
        const samples = series[c.id]
        if (!samples) return '–'
        return (
          <span className="containers__stat">
            <span className="containers__stat-value">{formatNumber(last(samples)!.cpu, ONE_DECIMAL)}%</span>
            <Sparkline values={samples.map((s) => s.cpu)} max={100} width={48} label={i18n.t('containers:ContainersPage.cpuHistoryOfName', { name: c.name })} />
          </span>
        )
      },
    },
    {
      id: 'memory',
      header: i18n.t('containers:ContainersPage.memory'),
      defaultWidth: 150,
      sortValue: (c) => last(series[c.id])?.memUsed ?? -1,
      render: (c) => {
        const samples = series[c.id]
        if (!samples) return '–'
        const now = last(samples)!
        return (
          <span className="containers__stat" title={now.memLimit > 0 ? i18n.t('containers:ContainersPage.ofSize', { size: formatSize(now.memLimit) }) : undefined}>
            <span className="containers__stat-value">{formatSize(now.memUsed)}</span>
            <Sparkline values={samples.map((s) => s.memUsed)} max={now.memLimit} width={48} label={i18n.t('containers:ContainersPage.memoryHistoryOfName', { name: c.name })} />
          </span>
        )
      },
    },
  ]
}

const LABELS: Record<ContainerAction, [label: string, pending: string]> = {
  get start(): [string, string] { return [i18n.t('containers:ContainersPage.start'), i18n.t('containers:ContainersPage.starting')] },
  get stop(): [string, string] { return [i18n.t('containers:ContainersPage.stop'), i18n.t('containers:ContainersPage.stopping')] },
  get restart(): [string, string] { return [i18n.t('containers:ContainersPage.restart'), i18n.t('containers:ContainersPage.restarting')] },
  get pause(): [string, string] { return [i18n.t('containers:ContainersPage.pause'), i18n.t('containers:ContainersPage.pausing')] },
  get unpause(): [string, string] { return [i18n.t('containers:ContainersPage.unpause'), i18n.t('containers:ContainersPage.unpausing')] },
  get kill(): [string, string] { return [i18n.t('containers:ContainersPage.kill'), i18n.t('containers:ContainersPage.killing')] },
}

const ICON_PROPS = { size: 16, strokeWidth: 1.75 } as const
const MENU_ICONS: Record<ContainerAction, ReactNode> = {
  start: <Play {...ICON_PROPS} />,
  stop: <Square {...ICON_PROPS} />,
  restart: <RotateCw {...ICON_PROPS} />,
  pause: <Pause {...ICON_PROPS} />,
  unpause: <Play {...ICON_PROPS} />,
  kill: <Zap {...ICON_PROPS} />,
}
const TOGGLE_ICONS = { start: Play, stop: Square, unpause: Play } as const
const isToggle = (a: ContainerAction): a is keyof typeof TOGGLE_ICONS => a in TOGGLE_ICONS

/** Looks up the managed deployment only once a delete is requested, so plain browsing costs no extra request. */
function DeleteContainerDialog({ hostId, container, onClose }: { hostId: string; container: Container; onClose: () => void }) {
  const { t } = useTranslation('containers')
  const deployments = useDeployments(hostId)
  const del = useDeleteContainer(hostId)
  const managed = deployments.data?.some((d) => d.name === container.composeProject && d.managedDeploymentId)
  return (
    <DeleteResourceDialog
      kind="container"
      name={container.name}
      forceOption={container.state === 'running'}
      warning={managed ? t('ContainersPage.belongsToDeploymentComposeprojectAnd', { composeProject: container.composeProject }) : null}
      onDelete={(force) => del.mutateAsync({ id: container.id, force })}
      onClose={onClose}
    />
  )
}

/** `/environments/:id/containers`: all containers of one environment, optionally `?deployment=<project>`. */
export function ContainersPage() {
  const { t } = useTranslation('containers')
  const { id = '' } = useParams()
  const [params, setParams] = useSearchParams()
  const deployment = params.get('deployment')
  const { data: user } = useCurrentUser()
  const canWrite = user?.role === 'ADMIN' || user?.role === 'OPERATOR'
  const { data, isPending, error, refetch } = useContainers(id)
  const [target, setTarget] = useState<Container | null>(null)
  const [creating, setCreating] = useState(false)
  const act = useContainerAction(id)
  const [killTarget, setKillTarget] = useState<Container | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)
  const pending = act.isPending ? act.variables : null

  function run(c: Container, action: ContainerAction) {
    setActionError(null)
    act.mutate(
      { id: c.id, action },
      {
        onError: (e) => setActionError(t('ContainersPage.actionFailed', { action: LABELS[action][0], name: c.name, error: describeActionError(e) })),
        onSettled: () => setKillTarget(null),
      },
    )
  }
  const isAdmin = user?.role === 'ADMIN'
  const canConsole = useCanOpenConsole()
  const navigate = useNavigate()
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
  const baseColumns = useMemo(() => baseColumnsFor(updates, pending), [updates, pending])
  const columns = useMemo(
    () => [...baseColumns.slice(0, 3), ...statsColumns(series), ...baseColumns.slice(3)],
    [baseColumns, series],
  )

  function rowActions(c: Container): RowActions {
    const available = actionsFor(c.state)
    const toggle = available.find(isToggle)
    const busy = act.isPending
    const primary: NonNullable<RowActions['primary']> = []
    if (canWrite && toggle) {
      primary.push({ label: LABELS[toggle][0], icon: TOGGLE_ICONS[toggle], onSelect: () => run(c, toggle), disabled: busy })
    }
    primary.push({ label: t('ContainersPage.logs'), icon: ScrollText, onSelect: () => navigate(`${c.id}/logs`) })
    if (canConsole && c.state === 'running') {
      primary.push({ label: t('ContainersPage.console'), icon: SquareTerminal, onSelect: () => navigate(`${c.id}/console`) })
    }
    const menu: MenuEntry[] = []
    if (canWrite) {
      for (const a of available) {
        if (a === toggle || a === 'kill') continue
        menu.push({ label: LABELS[a][0], icon: MENU_ICONS[a], onSelect: () => run(c, a), disabled: busy })
      }
    }
    menu.push({ label: t('ContainersPage.details'), icon: <Info {...ICON_PROPS} />, onSelect: () => navigate(c.id) })
    if (canWrite) {
      menu.push({ type: 'separator' })
      if (available.includes('kill')) {
        menu.push({ label: t('ContainersPage.kill'), icon: MENU_ICONS.kill, danger: true, disabled: busy, onSelect: () => setKillTarget(c) })
      }
      menu.push({ label: t('ContainersPage.delete'), icon: <Trash2 {...ICON_PROPS} />, danger: true, onSelect: () => setTarget(c) })
    }
    return { primary, menu }
  }

  const rows = deployment ? data?.filter((c) => c.composeProject === deployment) : data

  return (
    <section className="containers">
      <PageHeader
        title={t('ContainersPage.containers')}
        actions={
          canWrite ? (
            <Button variant="primary" onClick={() => setCreating(true)}>
              {t('ContainersPage.createContainer')}
            </Button>
          ) : undefined
        }
      />
      {actionError && (
        <p role="alert" className="ui-error">
          {actionError}
        </p>
      )}
      <ResourceTable
        label={t('ContainersPage.containers2')}
        columns={columns}
        rows={rows}
        rowKey={(c) => c.id}
        searchText={(c) => `${c.name} ${c.image} ${c.composeProject ?? ''}`}
        defaultSort="name"
        rowActions={rowActions}
        rowLabel={(c) => c.name}
        emptyMessage={deployment ? t('ContainersPage.noContainersInDeploymentDeployment', { deployment }) : t('ContainersPage.noContainersInThisEnvironment')}
        isPending={isPending}
        error={error ? describeResourceError(error) : null}
        onRetry={() => void refetch()}
        toolbar={
          deployment && (
            <button type="button" className="containers__chip" onClick={() => setParams({})}>
              {t('ContainersPage.deploymentDeployment', { deployment })} <X size={14} strokeWidth={2} aria-hidden="true" />
            </button>
          )
        }
      />
      {killTarget && (
        <ConfirmDialog
          title={t('ContainersPage.killContainer')}
          confirmLabel={act.isPending ? t('ContainersPage.killing') : t('ContainersPage.kill')}
          danger
          pending={act.isPending}
          onConfirm={() => run(killTarget, 'kill')}
          onClose={() => setKillTarget(null)}
        >
          {t('ContainersPage.killNameImmediatelyTheContainer', { name: killTarget.name })}
        </ConfirmDialog>
      )}
      {creating && <CreateContainerDialog hostId={id} isAdmin={isAdmin} onClose={() => setCreating(false)} />}
      {target && <DeleteContainerDialog hostId={id} container={target} onClose={() => setTarget(null)} />}
    </section>
  )
}
