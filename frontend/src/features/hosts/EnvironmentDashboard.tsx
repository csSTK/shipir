import { useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { ChevronLeft, Sparkles } from 'lucide-react'
import { Badge, Button, Card, PageHeader, Skeleton } from '../../shared/ui/index.ts'
import { useCurrentUser } from '../auth/index.ts'
import { describeError, countOf, formatMemory } from './format.ts'
import { useHostsOverview, type HostOverview } from './api.ts'
import { HostUsageHistory } from '../usage-history/UsageHistory.tsx'
import { ENV_RESOURCES, type EnvResourceSlug } from './resources.ts'
import { EnvironmentActions } from './EnvironmentActions.tsx'
import { PruneDialog } from './PruneDialog.tsx'
import { DiskUsageCard } from './DiskUsageCard.tsx'
import { joinRows } from '../deployments/joinRows.ts'
import { useDeployments, useManagedDeployments } from '../deployments/api.ts'
import { useNetworks } from '../networks/api.ts'
import { useTranslation } from 'react-i18next'
import './EnvironmentsOverview.css'
import './EnvironmentDashboard.css'

/** Count shown on a resource tile; null when the overview does not provide it. */
function countFor(
  env: HostOverview,
  slug: EnvResourceSlug,
  listed: { deployments?: number; networks?: number },
): number | null {
  switch (slug) {
    case 'containers':
      return env.containers ? env.containers.running + env.containers.stopped : null
    case 'images':
      return env.images
    case 'volumes':
      return env.volumes
    case 'deployments':
      return listed.deployments ?? null
    case 'networks':
      return listed.networks ?? null
    default:
      return null
  }
}

function Back() {
  const { t } = useTranslation('hosts')
  return (
    <Link className="envs__back" to="/">
      <ChevronLeft size={16} strokeWidth={1.75} aria-hidden="true" /> {t('EnvironmentDashboard.environments')}
    </Link>
  )
}

/** Resource tiles; deployments and networks are not in the overview, so they come from their own lists (running environments only). */
function ResourceTiles({ env, up }: { env: HostOverview; up: boolean }) {
  const deployments = useDeployments(env.id, up)
  const managed = useManagedDeployments(env.id, up)
  const networks = useNetworks(env.id, up)
  const listed = { // Same union as the Deployments page: live projects plus managed ones that were never deployed.
    deployments: deployments.data ? joinRows(deployments.data, managed.data ?? []).length : undefined, networks: networks.data?.length }
  return (
      <ul className="dash__tiles">
        {ENV_RESOURCES.map(({ slug, label, icon: Icon, tone }) => {
          const count = up ? countFor(env, slug, listed) : null
          return (
            <li key={slug}>
              <Link className="dash__tile" to={`/environments/${env.id}/${slug}`}>
                <Card className="dash__card">
                  <span className={`dash__icon dash__icon--${tone}`} aria-hidden="true">
                    <Icon size={20} strokeWidth={1.75} />
                  </span>
                  <span className="dash__label">{label}</span>
                  <span className="dash__count">{count ?? '–'}</span>
                </Card>
              </Link>
            </li>
          )
        })}
      </ul>
  )
}

/** `/environments/:id`: live status and resource counts of one environment. */
export function EnvironmentDashboard() {
  const { t } = useTranslation('hosts')
  const { id } = useParams()
  const navigate = useNavigate()
  const { data, isPending, isError } = useHostsOverview()
  const env = data?.find((e) => e.id === id)
  const { data: user } = useCurrentUser()
  const canClean = user?.role === 'ADMIN' || user?.role === 'OPERATOR'
  const [pruning, setPruning] = useState(false)

  if (isPending) {
    return (
      <section className="ui-page envs" role="status" aria-label={t('EnvironmentDashboard.loadingEnvironment')} aria-busy="true">
        <Skeleton height="2rem" width="12rem" />
        <div className="dash__tiles">
          {ENV_RESOURCES.map(({ slug }) => (
            <Skeleton key={slug} height="6rem" />
          ))}
        </div>
      </section>
    )
  }
  if (isError) {
    return (
      <section className="ui-page envs">
        <Back />
        <p role="alert" className="envs__message">
          {t('EnvironmentDashboard.couldNotLoadTheEnvironment')}
        </p>
      </section>
    )
  }
  if (!env) {
    return (
      <section className="ui-page envs">
        <Back />
        <PageHeader title={t('EnvironmentDashboard.environmentNotFound')} subtitle={t('EnvironmentDashboard.thisEnvironmentDoesNotExist')} />
      </section>
    )
  }

  const up = env.status === 'UP'
  const meta = [
    env.dockerVersion && t('EnvironmentDashboard.dockerDockerversion', { dockerVersion: env.dockerVersion }),
    env.cpus !== null && countOf(env.cpus, 'cpu'),
    env.memoryBytes !== null && formatMemory(env.memoryBytes),
  ]
    .filter(Boolean)
    .join(' · ')
  return (
    <section className="ui-page envs">
      <Back />
      <PageHeader
        title={env.name}
        subtitle={
          <span className="dash__subtitle">
            <Badge tone={up ? 'ok' : 'bad'}>{up ? t('EnvironmentDashboard.up') : t('EnvironmentDashboard.down')}</Badge>
            {up && meta && <span className="dash__meta">{meta}</span>}
          </span>
        }
        actions={
          <>
            {up && canClean && (
              <Button onClick={() => setPruning(true)}>
                <Sparkles size={16} strokeWidth={1.75} aria-hidden="true" /> {t('EnvironmentDashboard.cleanUp')}
              </Button>
            )}
            {user?.role === 'ADMIN' && <EnvironmentActions env={env} onDeleted={() => navigate('/')} />}
          </>
        }
      />
      {!up && (
        <p role="alert" className="env-card__error">
          {describeError(env.error)}
        </p>
      )}
      <ResourceTiles env={env} up={up} />
      {up && (
        <div className="dash__disk">
          <DiskUsageCard hostId={env.id} onCleanUp={canClean ? () => setPruning(true) : undefined} />
        </div>
      )}
      {up && <HostUsageHistory hostId={env.id} />}
      <PruneDialog hostId={env.id} open={pruning} onClose={() => setPruning(false)} />
    </section>
  )
}
