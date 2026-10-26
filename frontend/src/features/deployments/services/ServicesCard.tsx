import { Download, EllipsisVertical, Layers, Play, RotateCw } from 'lucide-react'
import { useState } from 'react'
import { ApiError } from '../../../shared/api/client.ts'
import { Button, Card, EmptyState, IconButton, Menu, Skeleton } from '../../../shared/ui/index.ts'
import { describeDeploymentError } from '../deploymentErrors.ts'
import { useServiceAction, useServices, useSetProfiles, useStartAll, type ServiceAction } from './api.ts'
import './services.css'

const LABEL: Record<ServiceAction, string> = { restart: 'Restart', pull: 'Pull', up: 'Up' }

function sameSet(a: string[], b: string[]): boolean {
  return a.length === b.length && a.every((x) => b.includes(x))
}

/** Profile checkboxes; remounted (via `key`) when the saved profiles change, so the draft resets. */
function ProfilePicker({ hostId, deploymentId, available, active }: { hostId: string; deploymentId: string; available: string[]; active: string[] }) {
  const save = useSetProfiles(hostId, deploymentId)
  const [selected, setSelected] = useState<string[]>(active)
  const [error, setError] = useState<string | null>(null)
  // Active profiles the file no longer defines stay visible so they can be removed.
  const names = [...new Set([...available, ...active])].sort()

  if (names.length === 0) {
    return <p className="services__text">This Compose file defines no profiles.</p>
  }

  const toggle = (name: string) => setSelected((cur) => (cur.includes(name) ? cur.filter((n) => n !== name) : [...cur, name]))

  return (
    <div className="services__profiles">
      <fieldset className="services__fieldset">
        <legend className="services__legend">Active profiles</legend>
        <div className="services__chips">
          {names.map((name) => (
            <label key={name} className="services__chip">
              <input type="checkbox" checked={selected.includes(name)} onChange={() => toggle(name)} />
              <span>{name}</span>
            </label>
          ))}
        </div>
      </fieldset>
      <p className="services__text">Services behind an active profile are part of every deploy, start and update.</p>
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
            save.mutate([...selected].sort(), { onError: (e) => setError(describeDeploymentError(e, 'Could not save the profiles. Try again.')) })
          }}
        >
          Save profiles
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
    const kind = `${LABEL[act]} ${service}`
    action.mutate(
      { service, action: act },
      {
        onSuccess: (r) => onRunStarted(kind, r.runId),
        onError: (e) => setError(describeDeploymentError(e, `Could not start "${kind}". Try again.`)),
      },
    )
  }

  function start() {
    setError(null)
    startAll.mutate(undefined, {
      onSuccess: (r) => onRunStarted('Start', r.runId),
      onError: (e) => setError(describeDeploymentError(e, 'Could not start the services. Try again.')),
    })
  }

  return (
    <Card className="services" role="region" aria-labelledby={titleId}>
      <div className="services__head">
        <h3 className="services__title" id={titleId}>
          Services
        </h3>
        {info.data && info.data.services.length > 0 && (
          <Button variant="secondary" onClick={start} disabled={pending}>
            Start all
          </Button>
        )}
      </div>

      {info.isPending && <Skeleton height="var(--space-6)" />}
      {info.isError && (
        <div className="services__error">
          <p role="alert" className="ui-error">
            Could not load the services.
          </p>
          <Button onClick={() => info.refetch()}>Retry</Button>
        </div>
      )}
      {info.data?.services.length === 0 && <EmptyState icon={Layers} title="No services" description="The Compose file does not define any service." />}
      {info.data && info.data.services.length > 0 && (
        <ul className="services__list" aria-label="Services">
          {info.data.services.map((name) => (
            <li key={name} className="services__row">
              <span className="services__name">{name}</span>
              <div className="services__actions">
                <IconButton icon={RotateCw} aria-label={`Restart ${name}`} disabled={pending} onClick={() => run(name, 'restart')} />
                <IconButton icon={Download} aria-label={`Pull image of ${name}`} disabled={pending} onClick={() => run(name, 'pull')} />
                <Menu
                  trigger={<IconButton icon={EllipsisVertical} aria-label={`More actions for ${name}`} disabled={pending} />}
                  items={[{ label: 'Up', icon: <Play size={16} />, onSelect: () => run(name, 'up') }]}
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
