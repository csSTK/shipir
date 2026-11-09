import { useState, type ReactNode } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { ArrowLeft } from 'lucide-react'
import { useContainerStats, type StatSample } from '../../../shared/realtime/useContainerStats.ts'
import { Badge, Button, Card, PageHeader, Sparkline, StatusDot, Tabs } from '../../../shared/ui/index.ts'
import { formatSize } from '../../hosts/format.ts'
import { useCurrentUser } from '../../auth/index.ts'
import { ContainerUsageHistory } from '../../usage-history/UsageHistory.tsx'
import { ContainerResourcesDialog } from '../ContainerResourcesDialog.tsx'
import { CreateContainerDialog } from '../CreateContainerDialog.tsx'
import { PublishDialog } from '../../domains/index.ts'
import { ContainerFilesPanel } from '../files/ContainerFilesPanel.tsx'
import { describeDetailsError, isNotFound, useContainerDetails, type ContainerDetails, type ContainerPort } from './api.ts'
import { useCanOpenConsole } from '../exec/api.ts'
import { useTranslation } from 'react-i18next'
import { dateTimeFormat, formatNumber, ONE_DECIMAL } from '../../../shared/i18n/format.ts'
import './ContainerDetailsPage.css'

const dateFormat = dateTimeFormat({ dateStyle: 'medium', timeStyle: 'medium' })
const formatDate = (iso: string | null) => (iso ? dateFormat.format(new Date(iso)) : '–')

function stateTone(state: string): 'ok' | 'warn' | 'bad' | 'muted' {
  if (state === 'running') return 'ok'
  if (state === 'restarting' || state === 'paused') return 'warn'
  if (state === 'dead') return 'bad'
  return 'muted'
}

function healthTone(status: string): 'ok' | 'warn' | 'bad' {
  return status === 'healthy' ? 'ok' : status === 'unhealthy' ? 'bad' : 'warn'
}

function formatPort(p: ContainerPort): string {
  return p.hostPort == null ? `${p.containerPort}/${p.protocol}` : `${p.hostPort} → ${p.containerPort}/${p.protocol}`
}

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <Card className="cdetails__section" role="region" aria-labelledby={`cd-${title}`}>
      <h3 id={`cd-${title}`} className="cdetails__heading">
        {title}
      </h3>
      {children}
    </Card>
  )
}

function Empty({ children }: { children: ReactNode }) {
  return <p className="cdetails__empty">{children}</p>
}

function Overview({ c }: { c: ContainerDetails }) {
  const { t } = useTranslation('containers')
  const restart = c.restartPolicy.name
    ? c.restartPolicy.maxRetries > 0
      ? t('ContainerDetailsPage.restartPolicyMax', { policy: c.restartPolicy.name, max: c.restartPolicy.maxRetries })
      : c.restartPolicy.name
    : 'no'
  const rows: [string, ReactNode][] = [
    [t('ContainerDetailsPage.image'), c.image],
    [t('ContainerDetailsPage.created'), formatDate(c.created)],
    [t('ContainerDetailsPage.started'), formatDate(c.startedAt)],
    [t('ContainerDetailsPage.restartPolicy'), restart],
    [t('ContainerDetailsPage.restarts'), String(c.restartCount)],
    [t('ContainerDetailsPage.command'), c.command.length ? c.command.join(' ') : '–'],
    [t('ContainerDetailsPage.deployment'), c.composeProject ?? '–'],
    [t('ContainerDetailsPage.service'), c.composeService ?? '–'],
  ]
  return (
    <Section title={t('ContainerDetailsPage.overview')}>
      <dl className="cdetails__grid">
        {rows.map(([k, v]) => (
          <div key={k} className="cdetails__row">
            <dt>{k}</dt>
            <dd>{v}</dd>
          </div>
        ))}
      </dl>
      {c.ports.length > 0 && (
        <ul className="cdetails__chips" aria-label={t('ContainerDetailsPage.ports')}>
          {c.ports.map((p) => (
            <li key={`${p.protocol}${p.containerPort}${p.hostIp}${p.hostPort}`}>{formatPort(p)}</li>
          ))}
        </ul>
      )}
    </Section>
  )
}

/** Nominal frame interval of the stats topic; counters are turned into per-second rates with it. */
const STATS_INTERVAL_S = 2

/** Per-second rate between consecutive cumulative counters; a counter reset counts as 0. */
function rates(samples: StatSample[], pick: (s: StatSample) => number): number[] {
  return samples.slice(1).map((s, i) => Math.max(0, pick(s) - pick(samples[i])) / STATS_INTERVAL_S)
}

function Metric({ name, value, values, max }: { name: string; value: string; values: number[]; max?: number }) {
  const { t } = useTranslation('containers')
  return (
    <div className="cdetails__metric">
      <span className="cdetails__metric-name">{name}</span>
      <span className="cdetails__metric-value">{value}</span>
      <Sparkline values={values} max={max} width={160} height={36} label={t('ContainerDetailsPage.nameHistory', { name })} />
    </div>
  )
}

/** Live resource usage of a running container, fed by the host stats topic. */
function Stats({ hostId, containerId }: { hostId: string; containerId: string }) {
  const { t } = useTranslation('containers')
  const { series, code } = useContainerStats(hostId)
  const samples = series[containerId]
  const now = samples?.[samples.length - 1]
  const rate = (pick: (s: StatSample) => number) => {
    const r = samples ? rates(samples, pick) : []
    return { values: r, text: `${formatSize(r[r.length - 1] ?? 0)}/s` }
  }
  const rx = rate((s) => s.netRx)
  const tx = rate((s) => s.netTx)
  const read = rate((s) => s.blkRead)
  const write = rate((s) => s.blkWrite)
  return (
    <Section title={t('ContainerDetailsPage.resourceUsage')}>
      {!now ? (
        <Empty>{code ? t('ContainerDetailsPage.liveStatsAreCurrentlyUnavailable') : t('ContainerDetailsPage.waitingForTheFirstSample')}</Empty>
      ) : (
        <div className="cdetails__metrics">
          <Metric name={t('ContainerDetailsPage.cpu')} value={`${formatNumber(now.cpu, ONE_DECIMAL)}%`} values={samples.map((s) => s.cpu)} max={100} />
          <Metric
            name={t('ContainerDetailsPage.memory')}
            value={now.memLimit > 0 ? `${formatSize(now.memUsed)} / ${formatSize(now.memLimit)}` : formatSize(now.memUsed)}
            values={samples.map((s) => s.memUsed)}
            max={now.memLimit}
          />
          <Metric name={t('ContainerDetailsPage.networkIn')} value={rx.text} values={rx.values} />
          <Metric name={t('ContainerDetailsPage.networkOut')} value={tx.text} values={tx.values} />
          <Metric name={t('ContainerDetailsPage.diskRead')} value={read.text} values={read.values} />
          <Metric name={t('ContainerDetailsPage.diskWrite')} value={write.text} values={write.values} />
        </div>
      )}
    </Section>
  )
}

function Env({ env }: { env: ContainerDetails['env'] }) {
  const { t } = useTranslation('containers')
  const [revealed, setRevealed] = useState(false)
  const hasValues = env.some((e) => e.value !== null)
  return (
    <Section title={t('ContainerDetailsPage.environment')}>
      {env.length === 0 ? (
        <Empty>{t('ContainerDetailsPage.noEnvironmentVariables')}</Empty>
      ) : (
        <>
          {hasValues && (
            <Button variant="ghost" size="sm" className="cdetails__btn" onClick={() => setRevealed((r) => !r)}>
              {revealed ? t('ContainerDetailsPage.hideValues') : t('ContainerDetailsPage.revealValues')}
            </Button>
          )}
          <dl className="cdetails__env">
            {env.map((e, i) => (
              <div key={`${e.key}${i}`} className="cdetails__row">
                <dt>{e.key}</dt>
                <dd>
                  {e.value === null ? (
                    <span className="cdetails__muted">{t('ContainerDetailsPage.hiddenForYourRole')}</span>
                  ) : revealed ? (
                    <code>{e.value}</code>
                  ) : (
                    <span aria-label={t('ContainerDetailsPage.maskedValue')}>••••••••</span>
                  )}
                </dd>
              </div>
            ))}
          </dl>
        </>
      )}
    </Section>
  )
}

function Mounts({ mounts }: { mounts: ContainerDetails['mounts'] }) {
  const { t } = useTranslation('containers')
  return (
    <Section title={t('ContainerDetailsPage.mounts')}>
      {mounts.length === 0 ? (
        <Empty>{t('ContainerDetailsPage.noMounts')}</Empty>
      ) : (
        <ul className="cdetails__list">
          {mounts.map((m) => (
            <li key={m.destination} className="cdetails__item">
              <Badge tone="muted">{m.type}</Badge> <code>{m.source ?? m.name ?? '–'}</code> → <code>{m.destination}</code>
              {m.readOnly && <span className="cdetails__muted"> {t('ContainerDetailsPage.readOnly')}</span>}
            </li>
          ))}
        </ul>
      )}
    </Section>
  )
}

function Networks({ networks }: { networks: ContainerDetails['networks'] }) {
  const { t } = useTranslation('containers')
  return (
    <Section title={t('ContainerDetailsPage.network')}>
      {networks.length === 0 ? (
        <Empty>{t('ContainerDetailsPage.notConnectedToAnyNetwork')}</Empty>
      ) : (
        <ul className="cdetails__list">
          {networks.map((n) => (
            <li key={n.name} className="cdetails__item">
              <strong>{n.name}</strong>
              <dl className="cdetails__grid">
                {(
                  [
                    [t('ContainerDetailsPage.ipAddress'), n.ipAddress],
                    [t('ContainerDetailsPage.gateway'), n.gateway],
                    [t('ContainerDetailsPage.macAddress'), n.macAddress],
                    [t('ContainerDetailsPage.aliases'), n.aliases.join(', ')],
                  ] as const
                ).map(([k, v]) => (
                  <div key={k} className="cdetails__row">
                    <dt>{k}</dt>
                    <dd>{v || '–'}</dd>
                  </div>
                ))}
              </dl>
            </li>
          ))}
        </ul>
      )}
    </Section>
  )
}

function Labels({ labels }: { labels: Record<string, string> }) {
  const { t } = useTranslation('containers')
  const entries = Object.entries(labels).sort(([a], [b]) => a.localeCompare(b))
  return (
    <Section title={t('ContainerDetailsPage.labels')}>
      {entries.length === 0 ? (
        <Empty>{t('ContainerDetailsPage.noLabels')}</Empty>
      ) : (
        <dl className="cdetails__env">
          {entries.map(([k, v]) => (
            <div key={k} className="cdetails__row">
              <dt>{k}</dt>
              <dd>{v || '–'}</dd>
            </div>
          ))}
        </dl>
      )}
    </Section>
  )
}

function Health({ health }: { health: NonNullable<ContainerDetails['health']> }) {
  const { t } = useTranslation('containers')
  return (
    <Section title={t('ContainerDetailsPage.health')}>
      <p>
        <Badge tone={healthTone(health.status)}>{health.status}</Badge>
        {health.failingStreak > 0 && <span className="cdetails__muted"> {t('ContainerDetailsPage.failingstreakFailingInARow', { failingStreak: health.failingStreak })}</span>}
      </p>
      {health.recentLogs.length > 0 && (
        <ul className="cdetails__list">
          {health.recentLogs.map((l) => (
            <li key={l.start} className="cdetails__item">
              <span className="cdetails__muted">{t('ContainerDetailsPage.dateExitExitcode', { date: formatDate(l.start), exitCode: l.exitCode })}</span>
              <pre className="cdetails__output">{l.output}</pre>
            </li>
          ))}
        </ul>
      )}
    </Section>
  )
}

/** `/environments/:id/containers/:cid`: inspect data of one container, read-only; links to logs and (ADMIN, permitted OPERATOR) console. */
export function ContainerDetailsPage() {
  const { t } = useTranslation('containers')
  const { id: hostId = '', cid = '' } = useParams()
  const { data: user } = useCurrentUser()
  const canOpenConsole = useCanOpenConsole(hostId)
  const { data: c, isPending, error, refetch } = useContainerDetails(hostId, cid)
  const navigate = useNavigate()
  const [dialog, setDialog] = useState<'edit' | 'duplicate' | 'limits' | 'publish' | null>(null)
  const base = `/environments/${hostId}/containers`
  const canEdit = user?.role === 'ADMIN' || user?.role === 'OPERATOR'
  const needsAdmin = !!c && (c.privileged || c.hostNetwork) && user?.role !== 'ADMIN'

  const tabs = c
    ? [
        {
          value: 'overview',
          label: t('ContainerDetailsPage.overview'),
          content: (
            <div className="cdetails__stack">
              <Overview c={c} />
              {c.state === 'running' && <Stats hostId={hostId} containerId={c.id} />}
              <Section title={t('ContainerDetailsPage.usageHistory')}>
                <ContainerUsageHistory hostId={hostId} containerId={c.id} />
              </Section>
            </div>
          ),
        },
        { value: 'environment', label: t('ContainerDetailsPage.environment'), content: <Env env={c.env} /> },
        {
          value: 'storage',
          label: t('ContainerDetailsPage.storageNetwork'),
          content: (
            <div className="cdetails__stack">
              <Mounts mounts={c.mounts} />
              <Networks networks={c.networks} />
            </div>
          ),
        },
        { value: 'labels', label: t('ContainerDetailsPage.labels'), content: <Labels labels={c.labels} /> },
        ...(c.health ? [{ value: 'health', label: t('ContainerDetailsPage.health'), content: <Health health={c.health} /> }] : []),
        ...(user?.role === 'ADMIN'
          ? [{ value: 'files', label: t('ContainerDetailsPage.files'), content: <ContainerFilesPanel hostId={hostId} containerId={c.id} /> }]
          : []),
      ]
    : []

  return (
    <div className="ui-page cdetails">
      <Link to={base} className="cdetails__back">
        <ArrowLeft size={16} strokeWidth={1.75} aria-hidden="true" /> {t('ContainerDetailsPage.containers')}
      </Link>
      {isPending && <p role="status">{t('ContainerDetailsPage.loading')}</p>}
      {error && (
        <div role="alert" className="ui-error">
          <p>{isNotFound(error) ? t('ContainerDetailsPage.thisContainerNoLongerExists') : describeDetailsError(error)}</p>
          {!isNotFound(error) && (
            <Button variant="ghost" size="sm" onClick={() => void refetch()}>
              {t('ContainerDetailsPage.retry')}
            </Button>
          )}
        </div>
      )}
      {c && (
        <>
          <PageHeader
            title={c.name}
            subtitle={
              <span className="cdetails__sub">
                <Badge tone={stateTone(c.state)}>{c.state}</Badge>
                {c.health && (
                  <span className="cdetails__muted">
                    <StatusDot tone={healthTone(c.health.status)} /> {c.health.status}
                  </span>
                )}
              </span>
            }
            actions={
              <nav className="cdetails__nav" aria-label={t('ContainerDetailsPage.containerViews')}>
                <Button asChild>
                  <Link to={`${base}/${cid}/logs`}>{t('ContainerDetailsPage.logs')}</Link>
                </Button>
                {canOpenConsole(c.composeProject) && c.state === 'running' && (
                  <Button asChild>
                    <Link to={`${base}/${cid}/console`}>{t('ContainerDetailsPage.console')}</Link>
                  </Button>
                )}
              </nav>
            }
          />
          {canEdit && (
            <div className="cdetails__edit">
              <div className="cdetails__nav" role="group" aria-label={t('ContainerDetailsPage.changeContainer')}>
                <Button disabled={!!c.composeProject || needsAdmin} onClick={() => setDialog('edit')}>
                  {t('ContainerDetailsPage.edit')}
                </Button>
                <Button disabled={!!c.composeProject || needsAdmin} onClick={() => setDialog('duplicate')}>
                  {t('ContainerDetailsPage.duplicate')}
                </Button>
                <Button disabled={!!c.composeProject} onClick={() => setDialog('limits')}>
                  {t('ContainerDetailsPage.resourceLimits')}
                </Button>
                <Button onClick={() => setDialog('publish')}>{t('ContainerDetailsPage.publish')}</Button>
              </div>
              {!c.composeProject && needsAdmin && (
                <p className="cdetails__muted">{t('ContainerDetailsPage.thisContainerIsPrivilegedOr')}</p>
              )}
              {c.composeProject && (
                <p className="cdetails__muted">{t('ContainerDetailsPage.thisContainerIsManagedBy', { composeProject: c.composeProject })}</p>
              )}
            </div>
          )}
          <Tabs label={t('ContainerDetailsPage.containerSections')} items={tabs} />
          {(dialog === 'edit' || dialog === 'duplicate') && (
            <CreateContainerDialog
              hostId={hostId}
              isAdmin={user?.role === 'ADMIN'}
              mode={dialog}
              source={c}
              onClose={() => setDialog(null)}
              onDone={(newId) => void navigate(`${base}/${newId}`)}
            />
          )}
          {dialog === 'publish' && <PublishDialog hostId={hostId} containerName={c.name} onClose={() => setDialog(null)} />}
          {dialog === 'limits' && <ContainerResourcesDialog hostId={hostId} container={c} onClose={() => setDialog(null)} />}
        </>
      )}
    </div>
  )
}
