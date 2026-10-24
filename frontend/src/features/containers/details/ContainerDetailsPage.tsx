import { useState, type ReactNode } from 'react'
import { Link, useParams } from 'react-router-dom'
import { Badge } from '../../../shared/ui/index.ts'
import { useCurrentUser } from '../../auth/index.ts'
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
    <section className="cdetails__section" aria-labelledby={`cd-${title}`}>
      <h3 id={`cd-${title}`} className="cdetails__heading">
        {title}
      </h3>
      {children}
    </section>
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
            <button type="button" className="sk-btn sk-btn--plain cdetails__btn" onClick={() => setRevealed((r) => !r)}>
              {revealed ? 'Hide values' : 'Reveal values'}
            </button>
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

  return (
    <div className="cdetails">
      <Link to={base} className="cdetails__back">
        ← Containers
      </Link>
      {isPending && <p role="status">Loading…</p>}
      {error && (
        <div role="alert" className="sk-error">
          <p>{isNotFound(error) ? 'This container no longer exists.' : describeDetailsError(error)}</p>
          {!isNotFound(error) && (
            <button type="button" className="sk-btn sk-btn--plain cdetails__btn" onClick={() => void refetch()}>
              Retry
            </button>
          )}
        </div>
      )}
      {c && (
        <>
          <header className="cdetails__header">
            <h2 className="cdetails__title">{c.name}</h2>
            <Badge tone={stateTone(c.state)}>{c.state}</Badge>
            <nav className="cdetails__nav" aria-label="Container views">
              <Link className="sk-btn sk-btn--plain cdetails__btn" to={`${base}/${cid}/logs`}>
                Logs
              </Link>
              {user?.role === 'ADMIN' && c.state === 'running' && (
                <Link className="sk-btn sk-btn--plain cdetails__btn" to={`${base}/${cid}/console`}>
                  Console
                </Link>
              )}
            </nav>
          </header>
          <Overview c={c} />
          <Env env={c.env} />
          <Mounts mounts={c.mounts} />
          <Networks networks={c.networks} />
          <Labels labels={c.labels} />
          {c.health && <Health health={c.health} />}
        </>
      )}
    </div>
  )
}
