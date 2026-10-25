import { useState, type ReactNode } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { Plus, RefreshCw, Server } from 'lucide-react'
import { Badge, Button, Card, Dialog, EmptyState, PageHeader, SearchField, Skeleton, Toolbar } from '../../shared/ui/index.ts'
import { EnvironmentActions } from './EnvironmentActions.tsx'
import { EnvironmentForm } from './EnvironmentForm.tsx'
import { TagChips, TagFilter } from './TagChips.tsx'
import { describeError, formatMemory, plural } from './format.ts'
import { useHostsOverview, type HostOverview } from './api.ts'
import { envTags } from './tags.ts'
import './EnvironmentsOverview.css'

type Role = 'ADMIN' | 'OPERATOR' | 'VIEWER'

function Stat({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="env-stat">
      <dt>{label}</dt>
      <dd>{children}</dd>
    </div>
  )
}

function EnvironmentCard({ env, isAdmin }: { env: HostOverview; isAdmin: boolean }) {
  const up = env.status === 'UP'
  const message = up ? null : describeError(env.error)
  return (
    <li className="env-item">
      <Card className={`env-card env-card--${up ? 'up' : 'down'}`}>
        <Link className="env-card__link" to={`/environments/${env.id}`}>
          <div className="env-card__head">
            <span className="env-card__icon" aria-hidden="true">
              <Server size={20} strokeWidth={1.75} />
            </span>
            <div className="env-card__title">
              <span className="env-card__name">{env.name}</span>
              <span className="env-card__type">{env.connectionType === 'LOCAL' ? 'Local socket' : 'TCP + TLS'}</span>
            </div>
            <Badge tone={up ? 'ok' : 'bad'}>{up ? 'Up' : 'Down'}</Badge>
          </div>
          <div className="env-card__endpoint">{env.endpoint}</div>
          <TagChips tags={envTags(env)} />
          {message && <p className="env-card__error">{message}</p>}
          {up && env.containers && (
            <div className="env-card__chips">
              <Badge tone="ok">{`${env.containers.running} running`}</Badge>
              <Badge tone="muted">{`${env.containers.stopped} stopped`}</Badge>
              <Badge tone="ok" dot={false}>{`${env.containers.healthy} healthy`}</Badge>
              <Badge tone={env.containers.unhealthy > 0 ? 'bad' : 'muted'}>{`${env.containers.unhealthy} unhealthy`}</Badge>
            </div>
          )}
          {up && (
            <dl className="env-card__stats">
              {env.dockerVersion && <Stat label="Docker">{env.dockerVersion}</Stat>}
              {env.cpus !== null && <Stat label="CPU">{plural(env.cpus, 'CPU')}</Stat>}
              {env.memoryBytes !== null && <Stat label="Memory">{formatMemory(env.memoryBytes)}</Stat>}
              {env.images !== null && <Stat label="Images">{plural(env.images, 'image')}</Stat>}
              {env.volumes !== null && <Stat label="Volumes">{plural(env.volumes, 'volume')}</Stat>}
            </dl>
          )}
        </Link>
        {isAdmin && (
          <div className="env-card__menu">
            <EnvironmentActions env={env} />
          </div>
        )}
      </Card>
    </li>
  )
}

export function EnvironmentsOverview({ role }: { role: Role }) {
  const { data, isPending, isError, refetch, isFetching } = useHostsOverview()
  const [query, setQuery] = useState('')
  const [adding, setAdding] = useState(false)
  // The tag filter lives in the URL (`?tag=a&tag=b`): an environment must carry every selected tag.
  const [params, setParams] = useSearchParams()
  const selectedTags = params.getAll('tag')
  const isAdmin = role === 'ADMIN'

  const needle = query.trim().toLowerCase()
  const rows = (data ?? []).filter(
    (env) => env.name.toLowerCase().includes(needle) && selectedTags.every((tag) => envTags(env).includes(tag)),
  )
  // Selected tags stay listed even when no environment carries them, so they can be unselected.
  const filterTags = [...new Set([...(data ?? []).flatMap(envTags), ...selectedTags])].sort()

  function toggleTag(tag: string) {
    setParams((prev) => {
      const next = new URLSearchParams(prev)
      const current = next.getAll('tag')
      next.delete('tag')
      const updated = current.includes(tag) ? current.filter((t) => t !== tag) : [...current, tag]
      for (const t of updated) next.append('tag', t)
      return next
    })
  }
  const addButton = (
    <Button variant="primary" onClick={() => setAdding(true)}>
      <Plus size={16} strokeWidth={1.75} aria-hidden="true" /> Add environment
    </Button>
  )

  return (
    <section className="envs">
      <PageHeader
        title="Environments"
        subtitle="Docker hosts managed with Shipir."
        actions={
          <>
            <Button onClick={() => void refetch()} disabled={isFetching} aria-busy={isFetching}>
              <RefreshCw size={16} strokeWidth={1.75} aria-hidden="true" /> Refresh
            </Button>
            {isAdmin && addButton}
          </>
        }
      />
      <Toolbar label="Filter environments">
        <SearchField placeholder="Search" aria-label="Search environments" value={query} onChange={(e) => setQuery(e.target.value)} />
        {filterTags.length > 0 && <TagFilter tags={filterTags} selected={selectedTags} onToggle={toggleTag} />}
      </Toolbar>

      {isPending && (
        <ul className="envs__list" role="status" aria-label="Loading environments">
          {[0, 1, 2].map((i) => (
            <li key={i} aria-hidden="true">
              <Skeleton height="9rem" />
            </li>
          ))}
        </ul>
      )}
      {isError && (
        <p role="alert" className="envs__message">
          Could not load the environments. Try again with Refresh.
        </p>
      )}
      {data && data.length === 0 && (
        <EmptyState
          icon={Server}
          title={isAdmin ? 'No environments yet. Add your first Docker environment.' : 'No environments yet, ask an admin.'}
          action={isAdmin ? addButton : undefined}
        />
      )}
      {data && data.length > 0 && rows.length === 0 && selectedTags.length === 0 && (
        <EmptyState icon={Server} title={`No environment matches “${query.trim()}”.`} />
      )}
      {data && data.length > 0 && rows.length === 0 && selectedTags.length > 0 && needle === '' && (
        <EmptyState icon={Server} title="No environments with this tag" />
      )}
      {data && data.length > 0 && rows.length === 0 && selectedTags.length > 0 && needle !== '' && (
        <EmptyState icon={Server} title="No environments match your filters" />
      )}
      {rows.length > 0 && (
        <ul className="envs__list">
          {rows.map((env) => (
            <EnvironmentCard key={env.id} env={env} isAdmin={isAdmin} />
          ))}
        </ul>
      )}

      <Dialog open={adding} onOpenChange={setAdding} title="Add environment">
        <EnvironmentForm onCreated={() => setAdding(false)} />
      </Dialog>
    </section>
  )
}
