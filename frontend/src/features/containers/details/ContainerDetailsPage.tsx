import { useState, type ReactNode } from 'react'
import { Link, useParams } from 'react-router-dom'
import { ArrowLeft } from 'lucide-react'
import { useContainerStats, type StatSample } from '../../../shared/realtime/useContainerStats.ts'
import { Badge, Button, Card, PageHeader, Sparkline, StatusDot, Tabs } from '../../../shared/ui/index.ts'
import { formatSize } from '../../hosts/format.ts'
import { useCurrentUser } from '../../auth/index.ts'
import { ContainerFilesPanel } from '../files/ContainerFilesPanel.tsx'
import { describeDetailsError, isNotFound, useContainerDetails, type ContainerDetails, type ContainerPort } from './api.ts'
import './ContainerDetailsPage.css'

const dateFormat = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'medium' })
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
  const restart = c.restartPolicy.name
    ? c.restartPolicy.maxRetries > 0
      ? `${c.restartPolicy.name} (max ${c.restartPolicy.maxRetries})`
      : c.restartPolicy.name
    : 'no'
  const rows: [string, ReactNode][] = [
    ['Image', c.image],
    ['Created', formatDate(c.created)],
    ['Started', formatDate(c.startedAt)],
    ['Restart policy', restart],
    ['Restarts', String(c.restartCount)],
    ['Command', c.command.length ? c.command.join(' ') : '–'],
    ['Deployment', c.composeProject ?? '–'],
    ['Service', c.composeService ?? '–'],
  ]
  return (
    <Section title="Overview">
      <dl className="cdetails__grid">
        {rows.map(([k, v]) => (
          <div key={k} className="cdetails__row">
            <dt>{k}</dt>
            <dd>{v}</dd>
          </div>
        ))}
      </dl>
      {c.ports.length > 0 && (
        <ul className="cdetails__chips" aria-label="Ports">
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
  return (
    <div className="cdetails__metric">
      <span className="cdetails__metric-name">{name}</span>
      <span className="cdetails__metric-value">{value}</span>
      <Sparkline values={values} max={max} width={160} height={36} label={`${name} history`} />
    </div>
  )
}

/** Live resource usage of a running container, fed by the host stats topic. */
function Stats({ hostId, containerId }: { hostId: string; containerId: string }) {
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
    <Section title="Resource usage">
      {!now ? (
        <Empty>{code ? 'Live stats are currently unavailable.' : 'Waiting for the first sample…'}</Empty>
      ) : (
        <div className="cdetails__metrics">
          <Metric name="CPU" value={`${now.cpu.toFixed(1)}%`} values={samples.map((s) => s.cpu)} max={100} />
          <Metric
            name="Memory"
            value={now.memLimit > 0 ? `${formatSize(now.memUsed)} / ${formatSize(now.memLimit)}` : formatSize(now.memUsed)}
            values={samples.map((s) => s.memUsed)}
            max={now.memLimit}
          />
          <Metric name="Network in" value={rx.text} values={rx.values} />
          <Metric name="Network out" value={tx.text} values={tx.values} />
          <Metric name="Disk read" value={read.text} values={read.values} />
          <Metric name="Disk write" value={write.text} values={write.values} />
        </div>
      )}
    </Section>
  )
}

function Env({ env }: { env: ContainerDetails['env'] }) {
  const [revealed, setRevealed] = useState(false)
  const hasValues = env.some((e) => e.value !== null)
  return (
    <Section title="Environment">
      {env.length === 0 ? (
        <Empty>No environment variables.</Empty>
      ) : (
        <>
          {hasValues && (
            <Button variant="ghost" size="sm" className="cdetails__btn" onClick={() => setRevealed((r) => !r)}>
              {revealed ? 'Hide values' : 'Reveal values'}
            </Button>
          )}
          <dl className="cdetails__env">
            {env.map((e, i) => (
              <div key={`${e.key}${i}`} className="cdetails__row">
                <dt>{e.key}</dt>
                <dd>
                  {e.value === null ? (
                    <span className="cdetails__muted">Hidden for your role</span>
                  ) : revealed ? (
                    <code>{e.value}</code>
                  ) : (
                    <span aria-label="Masked value">••••••••</span>
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
  return (
    <Section title="Mounts">
      {mounts.length === 0 ? (
        <Empty>No mounts.</Empty>
      ) : (
        <ul className="cdetails__list">
          {mounts.map((m) => (
            <li key={m.destination} className="cdetails__item">
              <Badge tone="muted">{m.type}</Badge> <code>{m.source ?? m.name ?? '–'}</code> → <code>{m.destination}</code>
              {m.readOnly && <span className="cdetails__muted"> read-only</span>}
            </li>
          ))}
        </ul>
      )}
    </Section>
  )
}

function Networks({ networks }: { networks: ContainerDetails['networks'] }) {
  return (
    <Section title="Network">
      {networks.length === 0 ? (
        <Empty>Not connected to any network.</Empty>
      ) : (
        <ul className="cdetails__list">
          {networks.map((n) => (
            <li key={n.name} className="cdetails__item">
              <strong>{n.name}</strong>
              <dl className="cdetails__grid">
                {(
                  [
                    ['IP address', n.ipAddress],
                    ['Gateway', n.gateway],
                    ['MAC address', n.macAddress],
                    ['Aliases', n.aliases.join(', ')],
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
  const entries = Object.entries(labels).sort(([a], [b]) => a.localeCompare(b))
  return (
    <Section title="Labels">
      {entries.length === 0 ? (
        <Empty>No labels.</Empty>
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
  return (
    <Section title="Health">
      <p>
        <Badge tone={healthTone(health.status)}>{health.status}</Badge>
        {health.failingStreak > 0 && <span className="cdetails__muted"> {health.failingStreak} failing in a row</span>}
      </p>
      {health.recentLogs.length > 0 && (
        <ul className="cdetails__list">
          {health.recentLogs.map((l) => (
            <li key={l.start} className="cdetails__item">
              <span className="cdetails__muted">
                {formatDate(l.start)} · exit {l.exitCode}
              </span>
              <pre className="cdetails__output">{l.output}</pre>
            </li>
          ))}
        </ul>
      )}
    </Section>
  )
}

/** `/environments/:id/containers/:cid`: inspect data of one container, read-only; links to logs and (ADMIN) console. */
export function ContainerDetailsPage() {
  const { id: hostId = '', cid = '' } = useParams()
  const { data: user } = useCurrentUser()
  const { data: c, isPending, error, refetch } = useContainerDetails(hostId, cid)
  const base = `/environments/${hostId}/containers`

  const tabs = c
    ? [
        {
          value: 'overview',
          label: 'Overview',
          content: (
            <div className="cdetails__stack">
              <Overview c={c} />
              {c.state === 'running' && <Stats hostId={hostId} containerId={c.id} />}
            </div>
          ),
        },
        { value: 'environment', label: 'Environment', content: <Env env={c.env} /> },
        {
          value: 'storage',
          label: 'Storage & network',
          content: (
            <div className="cdetails__stack">
              <Mounts mounts={c.mounts} />
              <Networks networks={c.networks} />
            </div>
          ),
        },
        { value: 'labels', label: 'Labels', content: <Labels labels={c.labels} /> },
        ...(c.health ? [{ value: 'health', label: 'Health', content: <Health health={c.health} /> }] : []),
        ...(user?.role === 'ADMIN'
          ? [{ value: 'files', label: 'Files', content: <ContainerFilesPanel hostId={hostId} containerId={c.id} /> }]
          : []),
      ]
    : []

  return (
    <div className="cdetails">
      <Link to={base} className="cdetails__back">
        <ArrowLeft size={16} strokeWidth={1.75} aria-hidden="true" /> Containers
      </Link>
      {isPending && <p role="status">Loading…</p>}
      {error && (
        <div role="alert" className="ui-error">
          <p>{isNotFound(error) ? 'This container no longer exists.' : describeDetailsError(error)}</p>
          {!isNotFound(error) && (
            <Button variant="ghost" size="sm" onClick={() => void refetch()}>
              Retry
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
              <nav className="cdetails__nav" aria-label="Container views">
                <Link className="ui-btn ui-btn--secondary ui-btn--md" to={`${base}/${cid}/logs`}>
                  Logs
                </Link>
                {user?.role === 'ADMIN' && c.state === 'running' && (
                  <Link className="ui-btn ui-btn--secondary ui-btn--md" to={`${base}/${cid}/console`}>
                    Console
                  </Link>
                )}
              </nav>
            }
          />
          <Tabs label="Container sections" items={tabs} />
        </>
      )}
    </div>
  )
}
