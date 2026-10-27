import { Download, EllipsisVertical, Layers, Play, RotateCw } from 'lucide-react'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { ApiError } from '../../../shared/api/client.ts'
import { Button, Card, EmptyState, IconButton, Menu, Skeleton } from '../../../shared/ui/index.ts'
import { describeDeploymentError } from '../deploymentErrors.ts'
import { useServiceAction, useServices, useSetProfiles, useStartAll, type ServiceAction } from './api.ts'
import './services.css'

function sameSet(a: string[], b: string[]): boolean {
  return a.length === b.length && a.every((x) => b.includes(x))
}

/** Profile checkboxes; remounted (via `key`) when the saved profiles change, so the draft resets. */
function ProfilePicker({ hostId, deploymentId, available, active }: { hostId: string; deploymentId: string; available: string[]; active: string[] }) {
  const { t } = useTranslation('deployments')
  const save = useSetProfiles(hostId, deploymentId)
  const [selected, setSelected] = useState<string[]>(active)
  const [error, setError] = useState<string | null>(null)
  // Active profiles the file no longer defines stay visible so they can be removed.
  const names = [...new Set([...available, ...active])].sort()

  if (names.length === 0) {
    return <p className="services__text">{t('ServicesCard.noProfiles')}</p>
  }

  const toggle = (name: string) => setSelected((cur) => (cur.includes(name) ? cur.filter((n) => n !== name) : [...cur, name]))

  return (
    <div className="services__profiles">
      <fieldset className="services__fieldset">
        <legend className="services__legend">{t('ServicesCard.activeProfiles')}</legend>
        <div className="services__chips">
          {names.map((name) => (
            <label key={name} className="services__chip">
              <input type="checkbox" checked={selected.includes(name)} onChange={() => toggle(name)} />
              <span>{name}</span>
            </label>
          ))}
        </div>
      </fieldset>
      <p className="services__text">{t('ServicesCard.profilesHint')}</p>
      {error && (
        <p role="alert" className="ui-error">
          {error}
        </p>
      )}
      <div>
        <Button
          variant="secondary"
          disabled={save.isPending || sameSet(selected, active)}
          onClick={() => {
            setError(null)
            save.mutate([...selected].sort(), { onError: (e) => setError(describeDeploymentError(e, t('ServicesCard.saveProfilesFailed'))) })
          }}
        >
          {t('ServicesCard.saveProfiles')}
        </Button>
      </div>
    </div>
  )
}

/** Services and Compose profiles of a deployment, with per-service actions (ADMIN/OPERATOR only). */
export function ServicesCard({
  hostId,
  deploymentId,
  activeProfiles,
  canOperate,
  onRunStarted,
}: {
  hostId: string
  deploymentId: string
  activeProfiles: string[]
  canOperate: boolean
  /** Called with a label and the `runId` of a started run so the page can stream its output. */
  onRunStarted: (kind: string, runId: string) => void
}) {
  const { t } = useTranslation('deployments')
  const info = useServices(hostId, deploymentId, canOperate)
  const action = useServiceAction(hostId, deploymentId)
  const startAll = useStartAll(hostId, deploymentId)
  const [error, setError] = useState<string | null>(null)

  if (!canOperate) return null
  if (info.error instanceof ApiError && info.error.status === 403) return null

  const pending = action.isPending || startAll.isPending
  const titleId = `services-${deploymentId}`

  function run(service: string, act: ServiceAction) {
    setError(null)
    const kind = t('ServicesCard.actionService', { action: (act === 'restart' ? t('ServicesCard.restart') : act === 'pull' ? t('ServicesCard.pull') : t('ServicesCard.up')), service })
    action.mutate(
      { service, action: act },
      {
        onSuccess: (r) => onRunStarted(kind, r.runId),
        onError: (e) => setError(describeDeploymentError(e, t('ServicesCard.actionFailed', { kind }))),
      },
    )
  }

  function start() {
    setError(null)
    startAll.mutate(undefined, {
      onSuccess: (r) => onRunStarted(t('ServicesCard.start'), r.runId),
      onError: (e) => setError(describeDeploymentError(e, t('ServicesCard.startAllFailed'))),
    })
  }

  return (
    <Card className="services" role="region" aria-labelledby={titleId}>
      <div className="services__head">
        <h3 className="services__title" id={titleId}>
          {t('ServicesCard.services')}
        </h3>
        {info.data && info.data.services.length > 0 && (
          <Button variant="secondary" onClick={start} disabled={pending}>
            {t('ServicesCard.startAll')}
          </Button>
        )}
      </div>

      {info.isPending && <Skeleton height="var(--space-6)" />}
      {info.isError && (
        <div className="services__error">
          <p role="alert" className="ui-error">
            {t('ServicesCard.loadFailed')}
          </p>
          <Button onClick={() => info.refetch()}>{t('ServicesCard.retry')}</Button>
        </div>
      )}
      {info.data?.services.length === 0 && <EmptyState icon={Layers} title={t('ServicesCard.noServices')} description={t('ServicesCard.noServicesDescription')} />}
      {info.data && info.data.services.length > 0 && (
        <ul className="services__list" aria-label={t('ServicesCard.services')}>
          {info.data.services.map((name) => (
            <li key={name} className="services__row">
              <span className="services__name">{name}</span>
              <div className="services__actions">
                <IconButton icon={RotateCw} aria-label={t('ServicesCard.restartName', { name })} disabled={pending} onClick={() => run(name, 'restart')} />
                <IconButton icon={Download} aria-label={t('ServicesCard.pullImageOf', { name })} disabled={pending} onClick={() => run(name, 'pull')} />
                <Menu
                  trigger={<IconButton icon={EllipsisVertical} aria-label={t('ServicesCard.moreActionsFor', { name })} disabled={pending} />}
                  items={[{ label: t('ServicesCard.up'), icon: <Play size={16} />, onSelect: () => run(name, 'up') }]}
                />
              </div>
            </li>
          ))}
        </ul>
      )}
      {error && (
        <p role="alert" className="ui-error">
          {error}
        </p>
      )}
      {info.data && (
        <ProfilePicker
          key={[...activeProfiles].sort().join(',')}
          hostId={hostId}
          deploymentId={deploymentId}
          available={info.data.profiles}
          active={activeProfiles}
        />
      )}
    </Card>
  )
}
