import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { EnvironmentForm } from './EnvironmentForm.tsx'
import { describeError, formatMemory, plural } from './format.ts'
import { useHostsOverview, type HostOverview } from './api.ts'
import './EnvironmentsOverview.css'

type Role = 'ADMIN' | 'OPERATOR' | 'VIEWER'

function EnvironmentRow({ env }: { env: HostOverview }) {
  const up = env.status === 'UP'
  const message = up ? null : describeError(env.error)
  return (
    <li className="env-row">
      <Link className="env-row__main" to={`/environments/${env.id}`}>
        <div className="env-row__head">
          <span className="env-row__name">{env.name}</span>
          <span
            className={`env-badge env-badge--${up ? 'up' : 'down'}`}
            title={message ?? undefined}
          >
            {up ? 'Up' : 'Down'}
          </span>
          <span className="env-row__type">{env.connectionType === 'LOCAL' ? 'Local socket' : 'TCP + TLS'}</span>
        </div>
        <div className="env-row__endpoint">{env.endpoint}</div>
        {message && <p className="env-row__error">{message}</p>}
        {up && (
          <dl className="env-row__stats">
            {env.dockerVersion && <Stat label="Docker">{env.dockerVersion}</Stat>}
            {env.cpus !== null && <Stat label="CPU">{plural(env.cpus, 'CPU')}</Stat>}
            {env.memoryBytes !== null && <Stat label="Memory">{formatMemory(env.memoryBytes)}</Stat>}
            {env.containers && (
              <Stat label="Containers">
                <span>{env.containers.running} running</span>
                <span>{env.containers.stopped} stopped</span>
                <span>{env.containers.healthy} healthy</span>
                <span className={env.containers.unhealthy > 0 ? 'env-row__warn' : undefined}>
                  {env.containers.unhealthy} unhealthy
                </span>
              </Stat>
            )}
            {env.images !== null && <Stat label="Images">{plural(env.images, 'image')}</Stat>}
            {env.volumes !== null && <Stat label="Volumes">{plural(env.volumes, 'volume')}</Stat>}
          </dl>
        )}
      </Link>
      {/* Row actions (rename, delete) go here, next to the link, not inside it. */}
    </li>
  )
}

function Stat({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="env-stat">
      <dt>{label}</dt>
      <dd>{children}</dd>
    </div>
  )
}

function AddEnvironmentDialog({ onClose }: { onClose: () => void }) {
  const ref = useRef<HTMLDivElement>(null)
  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null
    ref.current?.focus()
    return () => previous?.focus()
  }, [])
  return (
    <div className="env-scrim" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div
        ref={ref}
        className="env-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="add-env-title"
        tabIndex={-1}
        onKeyDown={(e) => e.key === 'Escape' && onClose()}
      >
        <div className="env-dialog__head">
          <h2 id="add-env-title">Add environment</h2>
          <button type="button" className="env-btn env-btn--plain" onClick={onClose}>
            Cancel
          </button>
        </div>
        <EnvironmentForm onCreated={onClose} />
      </div>
    </div>
  )
}

export function EnvironmentsOverview({ role }: { role: Role }) {
  const { data, isPending, isError, refetch, isFetching } = useHostsOverview()
  const [query, setQuery] = useState('')
  const [adding, setAdding] = useState(false)
  const isAdmin = role === 'ADMIN'

  const needle = query.trim().toLowerCase()
  const rows = (data ?? []).filter((env) => env.name.toLowerCase().includes(needle))

  return (
    <section className="envs">
      <header className="envs__bar">
        <h2 className="envs__title">Environments</h2>
        <input
          type="search"
          className="envs__search"
          placeholder="Search"
          aria-label="Search environments"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
        />
        <button
          type="button"
          className="env-btn"
          onClick={() => void refetch()}
          disabled={isFetching}
          aria-busy={isFetching}
        >
          Refresh
        </button>
        {isAdmin && (
          <button type="button" className="env-btn env-btn--primary" onClick={() => setAdding(true)}>
            Add environment
          </button>
        )}
      </header>

      {isPending && (
        <ul className="envs__list" role="status" aria-label="Loading environments">
          {[0, 1, 2].map((i) => (
            <li key={i} className="env-row env-row--skeleton" aria-hidden="true" />
          ))}
        </ul>
      )}
      {isError && (
        <p role="alert" className="envs__message">
          Could not load the environments. Try again with Refresh.
        </p>
      )}
      {data && data.length === 0 && (
        <div className="envs__message">
          {isAdmin ? (
            <>
              <p>No environments yet. Add your first Docker environment.</p>
              <button type="button" className="env-btn env-btn--primary" onClick={() => setAdding(true)}>
                Add environment
              </button>
            </>
          ) : (
            <p>No environments yet, ask an admin.</p>
          )}
        </div>
      )}
      {data && data.length > 0 && rows.length === 0 && (
        <p className="envs__message">No environment matches “{query.trim()}”.</p>
      )}
      {rows.length > 0 && (
        <ul className="envs__list">
          {rows.map((env) => (
            <EnvironmentRow key={env.id} env={env} />
          ))}
        </ul>
      )}

      {adding && <AddEnvironmentDialog onClose={() => setAdding(false)} />}
    </section>
  )
}
