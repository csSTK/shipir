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
import { Button, Dialog, Select, Switch, TextField } from '../../shared/ui/index.ts'
import { useTranslation } from 'react-i18next'
import i18n from 'i18next'

const SUBMIT: Record<ContainerFormMode, [string, string]> = {
  get create(): [string, string] { return [i18n.t('containers:CreateContainerDialog.create'), i18n.t('containers:CreateContainerDialog.create')] },
  get edit(): [string, string] { return [i18n.t('containers:CreateContainerDialog.reviewChanges'), i18n.t('containers:CreateContainerDialog.recreateContainer')] },
  get duplicate(): [string, string] { return [i18n.t('containers:CreateContainerDialog.duplicate'), i18n.t('containers:CreateContainerDialog.duplicate')] },
}
const TITLES: Record<ContainerFormMode, string> = { get create() { return i18n.t('containers:CreateContainerDialog.createContainer') }, get edit() { return i18n.t('containers:CreateContainerDialog.editContainer') }, get duplicate() { return i18n.t('containers:CreateContainerDialog.duplicateContainer') } }

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
  const { t } = useTranslation('containers')
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
    <Dialog size="lg" open onOpenChange={(open) => !open && onClose()} title={TITLES[mode]}>
      <form className="ui-form ccd" onSubmit={submit} noValidate>
        <div className="ui-form-row">
          <TextField
            label={t('CreateContainerDialog.image')}
            value={form.image}
            onChange={(e) => set('image', e.target.value)}
            placeholder="nginx:1.27"
            error={show('image')}
            autoComplete="off"
            autoCapitalize="off"
            spellCheck={false}
            autoFocus
          />
          <TextField
            label={mode === 'create' ? t('CreateContainerDialog.nameOptional') : t('CreateContainerDialog.name')}
            value={form.name}
            disabled={mode === 'edit'}
            onChange={(e) => set('name', e.target.value)}
            error={show('name')}
            autoComplete="off"
            autoCapitalize="off"
            spellCheck={false}
          />
        </div>
        <TextField
          label={t('CreateContainerDialog.commandOptional')}
          value={form.command}
          onChange={(e) => set('command', e.target.value)}
          placeholder={t('CreateContainerDialog.sleep3600')}
          autoComplete="off"
          spellCheck={false}
        />
        {mode !== 'create' && <p className="ui-hint">{t('CreateContainerDialog.theEntrypointOfTheImage')}</p>}
        {mode !== 'create' && (source?.networks.length ?? 0) > 1 && (
          <p role="note" className="ui-hint">
            {t('CreateContainerDialog.thisContainerIsConnectedTo', { count: source?.networks.length ?? 0 })}
          </p>
        )}

        <fieldset className="ccd__group">
          <legend>{t('CreateContainerDialog.ports')}</legend>
          {form.ports.map((p, i) => (
            <div className="ccd__row" key={i}>
              <TextField
                label={t('CreateContainerDialog.portValueHostPort', { value: i + 1 })}
                hideLabel
                placeholder={t('CreateContainerDialog.hostRandom')}
                inputMode="numeric"
                value={p.hostPort}
                onChange={(e) => setPort(i, { hostPort: e.target.value })}
                aria-invalid={show(`port${i}host`) ? true : undefined}
              />
              <TextField
                label={t('CreateContainerDialog.portValueContainerPort', { value: i + 1 })}
                hideLabel
                placeholder={t('CreateContainerDialog.container')}
                inputMode="numeric"
                value={p.containerPort}
                onChange={(e) => setPort(i, { containerPort: e.target.value })}
                aria-invalid={show(`port${i}container`) ? true : undefined}
              />
              <Select
                label={t('CreateContainerDialog.portValueProtocol', { value: i + 1 })}
                hideLabel
                value={p.protocol}
                onChange={(e) => setPort(i, { protocol: e.target.value as 'tcp' | 'udp' })}
              >
                <option value="tcp">{t('CreateContainerDialog.tcp')}</option>
                <option value="udp">{t('CreateContainerDialog.udp')}</option>
              </Select>
              <Button variant="ghost" aria-label={t('CreateContainerDialog.removePortValue', { value: i + 1 })} onClick={() => set('ports', form.ports.filter((_, j) => j !== i))}>
                {t('CreateContainerDialog.remove')}
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
              {t('CreateContainerDialog.addPort')}
            </Button>
          </div>
        </fieldset>

        <fieldset className="ccd__group">
          <legend>{t('CreateContainerDialog.mounts')}</legend>
          {form.mounts.map((m, i) => (
            <div className="ccd__row ccd__row--mount" key={i}>
              {isAdmin ? (
                <Select
                  label={t('CreateContainerDialog.mountValueType', { value: i + 1 })}
                  hideLabel
                  value={m.type}
                  onChange={(e) => setMount(i, { type: e.target.value as 'VOLUME' | 'BIND' })}
                >
                  <option value="VOLUME">{t('CreateContainerDialog.volume')}</option>
                  <option value="BIND">{t('CreateContainerDialog.bindPath')}</option>
                </Select>
              ) : (
                <span className="ui-hint">{t('CreateContainerDialog.volume')}</span>
              )}
              <TextField
                label={t('CreateContainerDialog.mountValueSource', { value: i + 1 })}
                hideLabel
                placeholder={m.type === 'VOLUME' ? t('CreateContainerDialog.volumeName') : '/host/path'}
                value={m.source}
                onChange={(e) => setMount(i, { source: e.target.value })}
                aria-invalid={show(`mount${i}source`) ? true : undefined}
                autoComplete="off"
                spellCheck={false}
              />
              <TextField
                label={t('CreateContainerDialog.mountValueTarget', { value: i + 1 })}
                hideLabel
                placeholder="/container/path"
                value={m.target}
                onChange={(e) => setMount(i, { target: e.target.value })}
                aria-invalid={show(`mount${i}target`) ? true : undefined}
                autoComplete="off"
                spellCheck={false}
              />
              <Switch
                label={t('CreateContainerDialog.readOnly')}
                aria-label={t('CreateContainerDialog.mountValueReadOnly', { value: i + 1 })}
                checked={m.readOnly}
                onChange={(e) => setMount(i, { readOnly: e.target.checked })}
              />
              <Button variant="ghost" aria-label={t('CreateContainerDialog.removeMountValue', { value: i + 1 })} onClick={() => set('mounts', form.mounts.filter((_, j) => j !== i))}>
                {t('CreateContainerDialog.remove')}
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
              {t('CreateContainerDialog.addMount')}
            </Button>
          </div>
        </fieldset>

        <EnvEditor value={form.env} onChange={(env) => set('env', env)} />
        {fieldError('env')}

        <div className="ui-form-row">
          <TextField
            label={t('CreateContainerDialog.networkOptional')}
            value={form.network}
            onChange={(e) => set('network', e.target.value)}
            placeholder={t('CreateContainerDialog.bridge')}
            error={show('network')}
            autoComplete="off"
            spellCheck={false}
          />
          <Select label={t('CreateContainerDialog.restartPolicy')} value={form.restartPolicy} onChange={(e) => set('restartPolicy', e.target.value as RestartPolicy)}>
            <option value="no">{t('CreateContainerDialog.no')}</option>
            <option value="always">{t('CreateContainerDialog.always')}</option>
            <option value="unless-stopped">{t('CreateContainerDialog.unlessStopped')}</option>
            <option value="on-failure">{t('CreateContainerDialog.onFailure')}</option>
          </Select>
        </div>

        {mode !== 'edit' && <Switch label={t('CreateContainerDialog.startAfterCreating')} checked={form.start} onChange={(e) => set('start', e.target.checked)} />}
        {isAdmin && (
          <>
            <Switch label={t('CreateContainerDialog.useTheHostNetwork')} checked={form.hostNetwork} onChange={(e) => set('hostNetwork', e.target.checked)} />
            <Switch label={t('CreateContainerDialog.privilegedFullAccessToThe')} checked={form.privileged} onChange={(e) => set('privileged', e.target.checked)} />
          </>
        )}

        {error && (
          <p role="alert" className="ui-error">
            {error}
          </p>
        )}
        {confirming && (
          <p role="status" className="ui-dialog-text">
            {t('CreateContainerDialog.nameWillBeRecreatedFrom', { name: source?.name })}
          </p>
        )}
        <div className="ui-dialog-actions">
          <Button variant="ghost" onClick={confirming ? () => setConfirming(false) : onClose}>
            {confirming ? t('CreateContainerDialog.back') : t('CreateContainerDialog.cancel')}
          </Button>
          <Button variant="primary" type="submit" loading={pending}>
            {SUBMIT[mode][confirming ? 1 : 0]}
          </Button>
        </div>
      </form>
    </Dialog>
  )
}
