import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { useCurrentUser } from '../auth/index.ts'
import { describeError, formatMemory, plural } from './format.ts'
import { useHostsOverview, type HostOverview } from './api.ts'
import { ENV_RESOURCES, type EnvResourceSlug } from './resources.ts'
import { PruneDialog } from './PruneDialog.tsx'
import './EnvironmentsOverview.css'
import './EnvironmentDashboard.css'
import { Button } from '../../shared/ui/index.ts'

/** Count shown on a resource tile; null when the overview does not provide it. */
function countFor(env: HostOverview, slug: EnvResourceSlug): number | null {
  switch (slug) {
    case 'containers':
      return env.containers ? env.containers.running + env.containers.stopped : null
    case 'images':
      return env.images
    case 'volumes':
      return env.volumes
    default:
      return null
  }
}

function Back() {
  return (
    <Link className="envs__back" to="/">
      ‹ Environments
    </Link>
  )
}

/** `/environments/:id`: live status and resource counts of one environment. */
export function EnvironmentDashboard() {
  const { id } = useParams()
  const { data, isPending, isError } = useHostsOverview()
  const env = data?.find((e) => e.id === id)
  const { data: user } = useCurrentUser()
  const canClean = user?.role === 'ADMIN' || user?.role === 'OPERATOR'
  const [pruning, setPruning] = useState(false)

  if (isPending) {
    return <section className="envs" role="status" aria-label="Loading environment" aria-busy="true" />
  }
  if (isError) {
    return (
      <section className="envs">
        <Back />
        <p role="alert" className="envs__message">
          Could not load the environment.
        </p>
      </section>
    )
  }
  if (!env) {
    return (
      <section className="envs">
        <Back />
        <h2 className="envs__title">Environment not found</h2>
        <p className="envs__message">This environment does not exist or was deleted.</p>
      </section>
    )
  }

  const up = env.status === 'UP'
  return (
    <section className="envs">
      <Back />
      <header className="envs__bar">
        <h2 className="envs__title">{env.name}</h2>
        <span className={`env-badge env-badge--${up ? 'up' : 'down'}`}>{up ? 'Up' : 'Down'}</span>
        {up && canClean && (
          <Button variant="ghost" className="dash__clean" onClick={() => setPruning(true)}>
            Clean up
          </Button>
        )}
      </header>
      {up ? (
        <p className="dash__meta">
          {[
            env.dockerVersion && `Docker ${env.dockerVersion}`,
            env.cpus !== null && plural(env.cpus, 'CPU'),
            env.memoryBytes !== null && formatMemory(env.memoryBytes),
          ]
            .filter(Boolean)
            .join(' · ')}
        </p>
      ) : (
        <p role="alert" className="env-row__error">
          {describeError(env.error)}
        </p>
      )}
      <ul className="dash__tiles">
        {ENV_RESOURCES.map(({ slug, label }) => {
          const count = up ? countFor(env, slug) : null
          return (
            <li key={slug}>
              <Link className="dash__tile" to={`/environments/${env.id}/${slug}`}>
                <span className="dash__label">{label}</span>
                <span className="dash__count">{count ?? '–'}</span>
              </Link>
            </li>
          )
        })}
      </ul>
      {pruning && <PruneDialog hostId={env.id} onClose={() => setPruning(false)} />}
    </section>
  )
}
