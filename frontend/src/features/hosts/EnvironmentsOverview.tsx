import { useState, type ReactNode } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { Plus, RefreshCw, Server } from 'lucide-react'
import { Badge, Button, Card, Dialog, EmptyState, PageHeader, SearchField, Skeleton, Toolbar } from '../../shared/ui/index.ts'
import { EnvironmentActions } from './EnvironmentActions.tsx'
import { EnvironmentForm } from './EnvironmentForm.tsx'
import { TagChips, TagFilter } from './TagChips.tsx'
import { describeError, countOf, formatMemory } from './format.ts'
import { useHostsOverview, type HostOverview } from './api.ts'
import { envTags } from './tags.ts'
import { useTranslation } from 'react-i18next'
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
  const { t: tr } = useTranslation('hosts')
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
              <span className="env-card__type">{env.connectionType === 'LOCAL' ? tr('EnvironmentsOverview.localSocket') : tr('EnvironmentsOverview.tcpTls')}</span>
            </div>
            <Badge tone={up ? 'ok' : 'bad'}>{up ? tr('EnvironmentsOverview.up') : tr('EnvironmentsOverview.down')}</Badge>
          </div>
          <div className="env-card__endpoint">{env.endpoint}</div>
          <TagChips tags={envTags(env)} />
          {message && <p className="env-card__error">{message}</p>}
          {up && env.containers && (
            <div className="env-card__chips">
              <Badge tone="ok">{tr('EnvironmentsOverview.runningRunning', { running: env.containers.running })}</Badge>
              <Badge tone="muted">{tr('EnvironmentsOverview.stoppedStopped', { stopped: env.containers.stopped })}</Badge>
              <Badge tone="ok" dot={false}>{tr('EnvironmentsOverview.healthyHealthy', { healthy: env.containers.healthy })}</Badge>
              <Badge tone={env.containers.unhealthy > 0 ? 'bad' : 'muted'}>{tr('EnvironmentsOverview.unhealthyUnhealthy', { unhealthy: env.containers.unhealthy })}</Badge>
            </div>
          )}
          {up && (
            <dl className="env-card__stats">
              {env.dockerVersion && <Stat label={tr('EnvironmentsOverview.docker')}>{env.dockerVersion}</Stat>}
              {env.cpus !== null && <Stat label={tr('EnvironmentsOverview.cpu')}>{countOf(env.cpus, 'cpu')}</Stat>}
              {env.memoryBytes !== null && <Stat label={tr('EnvironmentsOverview.memory')}>{formatMemory(env.memoryBytes)}</Stat>}
              {env.images !== null && <Stat label={tr('EnvironmentsOverview.images')}>{countOf(env.images, 'image')}</Stat>}
              {env.volumes !== null && <Stat label={tr('EnvironmentsOverview.volumes')}>{countOf(env.volumes, 'volume')}</Stat>}
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
  const { t: tr } = useTranslation('hosts')
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
      <Plus size={16} strokeWidth={1.75} aria-hidden="true" /> {tr('EnvironmentsOverview.addEnvironment')}
    </Button>
  )

  return (
    <section className="ui-page envs">
      <PageHeader
        title={tr('EnvironmentsOverview.environments')}
        subtitle={tr('EnvironmentsOverview.dockerHostsManagedWithShipir')}
        actions={
          <>
            <Button onClick={() => void refetch()} disabled={isFetching} aria-busy={isFetching}>
              <RefreshCw size={16} strokeWidth={1.75} aria-hidden="true" /> {tr('EnvironmentsOverview.refresh')}
            </Button>
            {isAdmin && addButton}
          </>
        }
      />
      <Toolbar label={tr('EnvironmentsOverview.filterEnvironments')}>
        <SearchField placeholder={tr('EnvironmentsOverview.search')} aria-label={tr('EnvironmentsOverview.searchEnvironments')} value={query} onChange={(e) => setQuery(e.target.value)} />
        {filterTags.length > 0 && <TagFilter tags={filterTags} selected={selectedTags} onToggle={toggleTag} />}
      </Toolbar>

      {isPending && (
        <ul className="envs__list" role="status" aria-label={tr('EnvironmentsOverview.loadingEnvironments')}>
          {[0, 1, 2].map((i) => (
            <li key={i} aria-hidden="true">
              <Skeleton height="9rem" />
            </li>
          ))}
        </ul>
      )}
      {isError && (
        <p role="alert" className="envs__message">
          {tr('EnvironmentsOverview.couldNotLoadTheEnvironments')}
        </p>
      )}
      {data && data.length === 0 && (
        <EmptyState
          icon={Server}
          title={isAdmin ? tr('EnvironmentsOverview.noEnvironmentsYetAddYour') : tr('EnvironmentsOverview.noEnvironmentsYetAskAn')}
          action={isAdmin ? addButton : undefined}
        />
      )}
      {data && data.length > 0 && rows.length === 0 && selectedTags.length === 0 && (
        <EmptyState icon={Server} title={tr('EnvironmentsOverview.noEnvironmentMatchesTrim', { query: query.trim() })} />
      )}
      {data && data.length > 0 && rows.length === 0 && selectedTags.length > 0 && needle === '' && (
        <EmptyState icon={Server} title={tr('EnvironmentsOverview.noEnvironmentsWithThisTag')} />
      )}
      {data && data.length > 0 && rows.length === 0 && selectedTags.length > 0 && needle !== '' && (
        <EmptyState icon={Server} title={tr('EnvironmentsOverview.noEnvironmentsMatchYourFilters')} />
      )}
      {rows.length > 0 && (
        <ul className="envs__list">
          {rows.map((env) => (
            <EnvironmentCard key={env.id} env={env} isAdmin={isAdmin} />
          ))}
        </ul>
      )}

      <Dialog open={adding} onOpenChange={setAdding} title={tr('EnvironmentsOverview.addEnvironment')}>
        <EnvironmentForm onCreated={() => setAdding(false)} />
      </Dialog>
    </section>
  )
}
