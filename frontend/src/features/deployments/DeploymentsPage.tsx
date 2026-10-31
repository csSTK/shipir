import { useMemo, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { FileText, GitBranch, PackagePlus, Play, Square, Trash2 } from 'lucide-react'
import { describeResourceError } from '../hosts/format.ts'
import { useCurrentUser } from '../auth/index.ts'
import { Checkbox, Badge, Button, ConfirmDialog, PageHeader, ResourceTable, type MenuEntry, type ResourceColumn, type RowActions } from '../../shared/ui/index.ts'
import {
  useDeployDeployment,
  useManagedDeployments,
  useDeployments,
  useRemoveUnmanagedDeployment,
  useStopDeployment,
  type ManagedDeployment,
  type DeploymentStatus,
  type DeploymentSummary,
} from './api.ts'
import { AdoptDeploymentDialog } from './AdoptDeploymentDialog.tsx'
import { AddDeploymentDialog } from './AddDeploymentDialog.tsx'
import { describeDeploymentError } from './deploymentErrors.ts'
import { useTranslation } from 'react-i18next'
import { Trans } from '../../shared/i18n/Trans.tsx'
import i18n from 'i18next'
import './DeploymentsPage.css'

const STATUS: Record<DeploymentStatus, { label: string; tone: 'ok' | 'warn' | 'muted' }> = {
  RUNNING: { get label() { return i18n.t('deployments:DeploymentsPage.running') }, tone: 'ok' },
  PARTIAL: { get label() { return i18n.t('deployments:DeploymentsPage.partial') }, tone: 'warn' },
  STOPPED: { get label() { return i18n.t('deployments:DeploymentsPage.stopped') }, tone: 'muted' },
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

const isSystem = (r: Row) => !!(r.managed?.systemManaged || r.live?.systemManaged)

/** `/environments/:id/deployments`: Compose projects of one environment, plus managed deployments. */
export function DeploymentsPage() {
  const { t } = useTranslation('deployments')
  const { id = '' } = useParams()
  const navigate = useNavigate()
  const { data: user } = useCurrentUser()
  const canWrite = user?.role === 'ADMIN' || user?.role === 'OPERATOR'
  const isAdmin = user?.role === 'ADMIN'
  const live = useDeployments(id)
  const managed = useManagedDeployments(id)
  const deploy = useDeployDeployment(id)
  const stop = useStopDeployment(id)
  const remove = useRemoveUnmanagedDeployment(id)
  const [adding, setAdding] = useState(false)
  /** Unmanaged project being adopted. */
  const [adopting, setAdopting] = useState<string | null>(null)
  const [notice, setNotice] = useState<{ tone: 'ok' | 'error'; text: string } | null>(null)
  /** Unmanaged project awaiting confirmation of its removal. */
  const [removing, setRemoving] = useState<string | null>(null)
  const [removeVolumes, setRemoveVolumes] = useState(false)
  const [removeError, setRemoveError] = useState<string | null>(null)

  const rows = useMemo(
    () => (live.data ? joinRows(live.data, managed.data ?? []) : undefined),
    [live.data, managed.data],
  )

  function run(kind: 'deploy' | 'stop', deployment: ManagedDeployment) {
    setNotice(null)
    if (kind === 'deploy') {
      deploy.mutate(deployment.id, {
        onSuccess: (d) => setNotice({ tone: 'ok', text: t('DeploymentsPage.deploymentOfNameStartedId', { name: deployment.name, runId: d.runId }) }),
        onError: (e) => setNotice({ tone: 'error', text: describeDeploymentError(e, t('DeploymentsPage.couldNotDeployName', { name: deployment.name })) }),
      })
    } else {
      stop.mutate(deployment.id, {
        onSuccess: () => setNotice({ tone: 'ok', text: t('DeploymentsPage.nameStopped', { name: deployment.name }) }),
        onError: (e) => setNotice({ tone: 'error', text: describeDeploymentError(e, t('DeploymentsPage.couldNotStopName', { name: deployment.name })) }),
      })
    }
  }

  function closeRemove() {
    setRemoving(null)
    setRemoveVolumes(false)
    setRemoveError(null)
  }

  function confirmRemove() {
    if (!removing) return
    const name = removing
    remove.mutate(
      { name, removeVolumes },
      {
        onSuccess: () => {
          closeRemove()
          setNotice({ tone: 'ok', text: t('DeploymentsPage.nameRemoved', { name }) })
        },
        onError: (e) => setRemoveError(describeDeploymentError(e, t('DeploymentsPage.couldNotRemoveName', { name }))),
      },
    )
  }

  const columns: ResourceColumn<Row>[] = [
    {
      id: 'name',
      header: t('DeploymentsPage.name'),
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
      header: t('DeploymentsPage.status'),
      sortValue: (r) => r.live?.status ?? r.managed?.status ?? '',
      render: (r) => {
        if (r.live) return <Badge tone={STATUS[r.live.status].tone}>{STATUS[r.live.status].label}</Badge>
        return <Badge tone={r.managed?.status === 'ERROR' ? 'bad' : 'muted'}>{r.managed?.status === 'ERROR' ? t('DeploymentsPage.error') : t('DeploymentsPage.notDeployed')}</Badge>
      },
    },
    {
      id: 'source',
      header: t('DeploymentsPage.source'),
      sortValue: (r) => r.managed?.source ?? '',
      render: (r) =>
        r.managed ? (
          <span className="deployments__source">
            {r.managed.source === 'GIT' ? <GitBranch size={16} strokeWidth={1.75} aria-hidden="true" /> : <FileText size={16} strokeWidth={1.75} aria-hidden="true" />}
            {r.managed.source === 'GIT' ? t('DeploymentsPage.git') : t('DeploymentsPage.file')}
          </span>
        ) : (
          '–'
        ),
    },
    {
      id: 'update',
      header: t('DeploymentsPage.update'),
      sortValue: (r) => (r.live?.updateAvailable ? 1 : 0),
      render: (r) => {
        if (!r.live?.updateAvailable) return '–'
        const badge = <Badge tone="warn">{t('DeploymentsPage.updateAvailable')}</Badge>
        // Pull & update lives on the detail page of managed deployments.
        return r.managed ? (
          <Link className="deployments__link" to={`/environments/${id}/deployments/${r.managed.id}`} aria-label={t('DeploymentsPage.pullUpdateName', { name: r.name })}>
            {badge}
          </Link>
        ) : (
          badge
        )
      },
    },
    {
      id: 'running',
      header: t('DeploymentsPage.running'),
      sortValue: (r) => r.live?.runningCount ?? 0,
      render: (r) => (r.live ? `${r.live.runningCount} / ${r.live.containerCount}` : '–'),
    },
    { id: 'dir', header: t('DeploymentsPage.workingDirectory'), render: (r) => r.live?.workingDir ?? '–' },
    {
      id: 'managed',
      header: t('DeploymentsPage.managed'),
      sortValue: (r) => (r.managed ? 0 : 1),
      render: (r) =>
        isSystem(r) ? (
          <Link className="deployments__link" to={`/environments/${id}/domains`}>
            <Badge tone="muted">{t('DeploymentsPage.managedViaDomains')}</Badge>
          </Link>
        ) : r.managed ? (
          <Badge tone="ok">{t('DeploymentsPage.managed')}</Badge>
        ) : (
          '–'
        ),
    },
  ]
  const busy = deploy.isPending || stop.isPending
  function rowActions(r: Row): RowActions {
    const details: MenuEntry = r.managed
      ? { label: t('DeploymentsPage.openDetails'), onSelect: () => navigate(`/environments/${id}/deployments/${r.managed!.id}`) }
      : { label: t('DeploymentsPage.showContainers'), onSelect: () => navigate(`/environments/${id}/containers?deployment=${encodeURIComponent(r.name)}`) }
    // the managed proxy is operated through Domains; only an ADMIN may act on it here
    if (!canWrite || (isSystem(r) && !isAdmin)) return { menu: [details] }
    if (r.managed) {
      return {
        primary: [
          { label: t('DeploymentsPage.deploy'), icon: Play, onSelect: () => run('deploy', r.managed!), disabled: busy },
          { label: t('DeploymentsPage.stop'), icon: Square, onSelect: () => run('stop', r.managed!), disabled: busy },
        ],
        menu: [details],
      }
    }
    return {
      primary: r.live
        ? [
            { label: t('DeploymentsPage.adopt'), icon: PackagePlus, onSelect: () => setAdopting(r.name) },
            {
              label: t('DeploymentsPage.remove'),
              icon: Trash2,
              danger: true,
              disabled: remove.isPending,
              onSelect: () => {
                setNotice(null)
                setRemoveError(null)
                setRemoving(r.name)
              },
            },
          ]
        : [],
      menu: [details],
    }
  }

  return (
    <section className="deployments">
      <PageHeader
        title={t('DeploymentsPage.deployments')}
        actions={
          canWrite ? (
            <Button variant="primary" onClick={() => setAdding(true)}>
              {t('DeploymentsPage.addDeployment')}
            </Button>
          ) : undefined
        }
      />
      {notice && (
        <p role={notice.tone === 'error' ? 'alert' : 'status'} className={notice.tone === 'error' ? 'ui-error' : 'deployments__notice'}>
          {notice.text}
        </p>
      )}
      <ResourceTable
        label={t('DeploymentsPage.deployments2')}
        columns={columns}
        rows={rows}
        rowKey={(r) => r.managed?.id ?? r.name}
        searchText={(r) => `${r.name} ${r.live?.workingDir ?? ''}`}
        defaultSort="name"
        rowActions={rowActions}
        rowLabel={(r) => r.name}
        emptyMessage={t('DeploymentsPage.noDeploymentsInThisEnvironment')}
        isPending={live.isPending}
        error={live.error ? describeResourceError(live.error) : null}
        onRetry={() => void live.refetch()}
      />
      {adding && <AddDeploymentDialog hostId={id} onClose={() => setAdding(false)} />}
      {adopting && <AdoptDeploymentDialog hostId={id} project={adopting} onClose={() => setAdopting(null)} />}
      {removing && (
        <ConfirmDialog
          title={t('DeploymentsPage.removeDeployment')}
          confirmLabel={t('DeploymentsPage.remove')}
          danger
          loading={remove.isPending}
          error={removeError}
          onConfirm={confirmRemove}
          open onOpenChange={(open) => !open && closeRemove()}
        >
          <Trans t={t} i18nKey="DeploymentsPage.stopAndRemoveTheContainers" values={{ removing }} components={{ strong: <strong />, code: <code /> }} />
          <Checkbox className="deployments__check" label={t('DeploymentsPage.alsoRemoveVolumes')} checked={removeVolumes} onChange={(e) => setRemoveVolumes(e.target.checked)} />
          {removeVolumes ? t('DeploymentsPage.dataInItsVolumesWill') : t('DeploymentsPage.itsVolumesAreKept')}
        </ConfirmDialog>
      )}
    </section>
  )
}
