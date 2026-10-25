import { useState, type FormEvent } from 'react'
import { EnvEditor } from '../deployments/editor/EnvEditor.tsx'
import { describeCreateError } from '../hosts/createResource.ts'
import { describeEditError, useContainerSpecMutation, useCreateContainer, type RestartPolicy } from './api.ts'
import {
  buildContainerSpec,
  formFromDetails,
  INITIAL_CONTAINER_FORM,
  validateContainerForm,
  type ContainerFormMode,
  type FormState,
  type MountRow,
  type PortRow,
} from './containerForm.ts'
import type { ContainerDetails } from './details/api.ts'
import './CreateContainerDialog.css'
import { Button, Dialog } from '../../shared/ui/index.ts'

const SUBMIT: Record<ContainerFormMode, [string, string]> = {
  create: ['Create', 'Create'],
  edit: ['Review changes', 'Recreate container'],
  duplicate: ['Duplicate', 'Duplicate'],
}
const TITLES: Record<ContainerFormMode, string> = { create: 'Create container', edit: 'Edit container', duplicate: 'Duplicate container' }

interface Props {
  hostId: string
  isAdmin: boolean
  onClose: () => void
  /** `create` (default) makes a new container; `edit` recreates `source` with the changes; `duplicate` copies `source` under a new name. */
  mode?: ContainerFormMode
  /** Inspect data of the container to edit or duplicate; required for those modes. */
  source?: ContainerDetails
  /** Called with the id of the resulting container after an edit or duplicate; the dialog then closes. */
  onDone?: (containerId: string) => void
}

/**
 * Creates, edits (recreates) or duplicates a container. ADMIN-only options are not rendered for others.
 */
export function CreateContainerDialog({ hostId, isAdmin, onClose, mode = 'create', source, onDone }: Props) {
  const [form, setForm] = useState<FormState>(() => (mode !== 'create' && source ? formFromDetails(source, mode) : INITIAL_CONTAINER_FORM))
  const [confirming, setConfirming] = useState(false)
  const [touched, setTouched] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const create = useCreateContainer(hostId)
  const recreate = useContainerSpecMutation(hostId, source?.id ?? '', 'recreate')
  const duplicate = useContainerSpecMutation(hostId, source?.id ?? '', 'duplicate')
  const pending = create.isPending || recreate.isPending || duplicate.isPending
  const problems = validateContainerForm(form, isAdmin, mode === 'duplicate')
  const show = (key: string) => (touched ? problems[key] : undefined)
  const set = <K extends keyof FormState>(key: K, value: FormState[K]) => setForm((f) => ({ ...f, [key]: value }))
  const setPort = (i: number, patch: Partial<PortRow>) => set('ports', form.ports.map((p, j) => (j === i ? { ...p, ...patch } : p)))
  const setMount = (i: number, patch: Partial<MountRow>) => set('mounts', form.mounts.map((m, j) => (j === i ? { ...m, ...patch } : m)))

  function submit(e: FormEvent) {
    e.preventDefault()
    setTouched(true)
    if (Object.keys(problems).length > 0) return
    if (mode === 'edit' && !confirming) {
      setConfirming(true)
      return
    }
    setError(null)
    const spec = buildContainerSpec(form, isAdmin)
    if (mode === 'create') {
      create.mutate(spec, { onSuccess: onClose, onError: (err) => setError(describeCreateError(err, 'container', 'INVALID_CONTAINER_SPEC')) })
      return
    }
    const operation = mode === 'edit' ? recreate : duplicate
    operation.mutate(spec, {
      onSuccess: (created) => {
        onDone?.(created.id)
        onClose()
      },
      onError: (err) => {
        setConfirming(false)
        setError(describeEditError(err, mode === 'edit' ? 'recreate' : 'duplicate'))
      },
    })
  }

  const fieldError = (key: string) =>
    show(key) && (
      <span role="alert" className="ui-error">
        {problems[key]}
      </span>
    )

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()} title={TITLES[mode]}>
      <form className="ui-form ccd" onSubmit={submit} noValidate>
        <div className="ui-form-row">
          <div className="ccd__field">
            <label className="ui-form-field">
            Image
            <input
              value={form.image}
              onChange={(e) => set('image', e.target.value)}
              placeholder="nginx:1.27"
              aria-invalid={show('image') ? true : undefined}
              autoComplete="off"
              autoCapitalize="off"
              spellCheck={false}
              autoFocus
            />
            </label>
            {fieldError('image')}
          </div>
          <div className="ccd__field">
            <label className="ui-form-field">
            {mode === 'create' ? 'Name (optional)' : 'Name'}
            <input
              value={form.name}
              disabled={mode === 'edit'}
              onChange={(e) => set('name', e.target.value)}
              aria-invalid={show('name') ? true : undefined}
              autoComplete="off"
              autoCapitalize="off"
              spellCheck={false}
            />
            </label>
            {fieldError('name')}
          </div>
        </div>
        <label className="ui-form-field">
          Command (optional)
          <input value={form.command} onChange={(e) => set('command', e.target.value)} placeholder="sleep 3600" autoComplete="off" spellCheck={false} />
        </label>
        {mode !== 'create' && <p className="ui-hint">The entrypoint of the image is kept. Empty means the image default command. Quote arguments with spaces; inside double quotes use {'\\"'} and {'\\\\'} for a quote or backslash.</p>}
        {mode !== 'create' && (source?.networks.length ?? 0) > 1 && (
          <p role="note" className="ui-hint">
            This container is connected to {source?.networks.length} networks. Only one is kept; reconnect the others afterwards.
          </p>
        )}

        <fieldset className="ccd__group">
          <legend>Ports</legend>
          {form.ports.map((p, i) => (
            <div className="ccd__row" key={i}>
              <input
                aria-label={`Port ${i + 1} host port`}
                placeholder="Host (random)"
                inputMode="numeric"
                value={p.hostPort}
                onChange={(e) => setPort(i, { hostPort: e.target.value })}
                aria-invalid={show(`port${i}host`) ? true : undefined}
              />
              <input
                aria-label={`Port ${i + 1} container port`}
                placeholder="Container"
                inputMode="numeric"
                value={p.containerPort}
                onChange={(e) => setPort(i, { containerPort: e.target.value })}
                aria-invalid={show(`port${i}container`) ? true : undefined}
              />
              <select aria-label={`Port ${i + 1} protocol`} value={p.protocol} onChange={(e) => setPort(i, { protocol: e.target.value as 'tcp' | 'udp' })}>
                <option value="tcp">tcp</option>
                <option value="udp">udp</option>
              </select>
              <Button variant="ghost" aria-label={`Remove port ${i + 1}`} onClick={() => set('ports', form.ports.filter((_, j) => j !== i))}>
                Remove
              </Button>
              {(show(`port${i}host`) || show(`port${i}container`)) && (
                <span role="alert" className="ui-error ccd__row-error">
                  {problems[`port${i}host`] ?? problems[`port${i}container`]}
                </span>
              )}
            </div>
          ))}
          {fieldError('ports')}
          <div>
            <Button variant="ghost" onClick={() => set('ports', [...form.ports, { hostPort: '', containerPort: '', protocol: 'tcp' }])}>
              Add port
            </Button>
          </div>
        </fieldset>

        <fieldset className="ccd__group">
          <legend>Mounts</legend>
          {form.mounts.map((m, i) => (
            <div className="ccd__row ccd__row--mount" key={i}>
              {isAdmin ? (
                <select aria-label={`Mount ${i + 1} type`} value={m.type} onChange={(e) => setMount(i, { type: e.target.value as 'VOLUME' | 'BIND' })}>
                  <option value="VOLUME">Volume</option>
                  <option value="BIND">Bind path</option>
                </select>
              ) : (
                <span className="ui-hint">Volume</span>
              )}
              <input
                aria-label={`Mount ${i + 1} source`}
                placeholder={m.type === 'VOLUME' ? 'Volume name' : '/host/path'}
                value={m.source}
                onChange={(e) => setMount(i, { source: e.target.value })}
                aria-invalid={show(`mount${i}source`) ? true : undefined}
                autoComplete="off"
                spellCheck={false}
              />
              <input
                aria-label={`Mount ${i + 1} target`}
                placeholder="/container/path"
                value={m.target}
                onChange={(e) => setMount(i, { target: e.target.value })}
                aria-invalid={show(`mount${i}target`) ? true : undefined}
                autoComplete="off"
                spellCheck={false}
              />
              <label className="ccd__check">
                <input type="checkbox" checked={m.readOnly} onChange={(e) => setMount(i, { readOnly: e.target.checked })} aria-label={`Mount ${i + 1} read-only`} /> Read-only
              </label>
              <Button variant="ghost" aria-label={`Remove mount ${i + 1}`} onClick={() => set('mounts', form.mounts.filter((_, j) => j !== i))}>
                Remove
              </Button>
              {(show(`mount${i}source`) || show(`mount${i}target`)) && (
                <span role="alert" className="ui-error ccd__row-error">
                  {problems[`mount${i}source`] ?? problems[`mount${i}target`]}
                </span>
              )}
            </div>
          ))}
          <div>
            <Button variant="ghost" onClick={() => set('mounts', [...form.mounts, { type: 'VOLUME', source: '', target: '', readOnly: false }])}>
              Add mount
            </Button>
          </div>
        </fieldset>

        <EnvEditor value={form.env} onChange={(env) => set('env', env)} />
        {fieldError('env')}

        <div className="ui-form-row">
          <div className="ccd__field">
            <label className="ui-form-field">
            Network (optional)
            <input
              value={form.network}
              onChange={(e) => set('network', e.target.value)}
              placeholder="bridge"
              aria-invalid={show('network') ? true : undefined}
              autoComplete="off"
              spellCheck={false}
            />
            </label>
            {fieldError('network')}
          </div>
          <label className="ui-form-field">
            Restart policy
            <select value={form.restartPolicy} onChange={(e) => set('restartPolicy', e.target.value as RestartPolicy)}>
              <option value="no">no</option>
              <option value="always">always</option>
              <option value="unless-stopped">unless-stopped</option>
              <option value="on-failure">on-failure</option>
            </select>
          </label>
        </div>

        {mode !== 'edit' && (
          <label className="ccd__check">
            <input type="checkbox" checked={form.start} onChange={(e) => set('start', e.target.checked)} /> Start after creating
          </label>
        )}
        {isAdmin && (
          <>
            <label className="ccd__check">
              <input type="checkbox" checked={form.hostNetwork} onChange={(e) => set('hostNetwork', e.target.checked)} /> Use the host network
            </label>
            <label className="ccd__check">
              <input type="checkbox" checked={form.privileged} onChange={(e) => set('privileged', e.target.checked)} /> Privileged (full access to the host)
            </label>
          </>
        )}

        {error && (
          <p role="alert" className="ui-error">
            {error}
          </p>
        )}
        {confirming && (
          <p role="status" className="ui-dialog-text">
            {source?.name} will be recreated from the new settings. It is stopped and started again, so expect a brief downtime. Volumes are kept; changes
            that are not shown here, such as container-internal files, are lost.
          </p>
        )}
        <div className="ui-dialog-actions">
          <Button variant="ghost" onClick={confirming ? () => setConfirming(false) : onClose}>
            {confirming ? 'Back' : 'Cancel'}
          </Button>
          <Button variant="primary" type="submit" loading={pending}>
            {SUBMIT[mode][confirming ? 1 : 0]}
          </Button>
        </div>
      </form>
    </Dialog>
  )
}
