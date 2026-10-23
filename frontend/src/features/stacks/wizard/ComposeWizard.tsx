import { useId, useState, type ReactNode } from 'react'
import { ApiError } from '../../../shared/api/client.ts'
import { useGenerateCompose } from '../api.ts'
import { YamlEditor } from '../editor/index.ts'
import {
  buildSpec,
  emptyService,
  hasProblems,
  initialState,
  referencedEnvKeys,
  RESTART_POLICIES,
  validateWizard,
  type ResourceForm,
  type ServiceForm,
  type WizardState,
} from './model.ts'
import './wizard.css'

function Field({ label, error, children }: { label: string; error?: string; children: ReactNode }) {
  return (
    <label className="wz-field">
      <span>{label}</span>
      {children}
      {error && <small className="wz-field__error">{error}</small>}
    </label>
  )
}

function remove<T>(list: T[], index: number): T[] {
  return list.filter((_, i) => i !== index)
}
function patchAt<T>(list: T[], index: number, patch: Partial<T>): T[] {
  return list.map((item, i) => (i === index ? { ...item, ...patch } : item))
}

function Toggle({ name, label, options, selected, onChange }: { name: string; label: string; options: string[]; selected: string[]; onChange: (next: string[]) => void }) {
  if (options.length === 0) return null
  return (
    <fieldset className="wz-group">
      <legend>{label}</legend>
      <div className="wz-chips">
        {options.map((o) => (
          <label key={o} className="wz-chip">
            <input
              type="checkbox"
              name={name}
              checked={selected.includes(o)}
              onChange={(e) => onChange(e.target.checked ? [...selected, o] : selected.filter((x) => x !== o))}
            />
            <span>{o}</span>
          </label>
        ))}
      </div>
    </fieldset>
  )
}

function ResourceList({
  kind,
  title,
  rows,
  fields,
  onChange,
}: {
  kind: 'networks' | 'volumes'
  title: string
  rows: ResourceForm[]
  fields: Map<string, string>
  onChange: (next: ResourceForm[]) => void
}) {
  const singular = kind === 'networks' ? 'Network' : 'Volume'
  return (
    <section className="wz-section" aria-label={title}>
      <h3 className="wz-section__title">{title}</h3>
      {rows.length === 0 && <p className="wz-hint">None declared.</p>}
      {rows.map((r, i) => (
        <div className="wz-row wz-row--resource" key={i}>
          <Field label={`${singular} name`} error={fields.get(`${kind}.${i}.name`)}>
            <input aria-label={`${singular} ${i + 1} name`} value={r.name} onChange={(e) => onChange(patchAt(rows, i, { name: e.target.value }))} autoComplete="off" spellCheck={false} />
          </Field>
          <Field label="Driver (optional)" error={fields.get(`${kind}.${i}.driver`)}>
            <input aria-label={`${singular} ${i + 1} driver`} value={r.driver} onChange={(e) => onChange(patchAt(rows, i, { driver: e.target.value }))} autoComplete="off" spellCheck={false} />
          </Field>
          <label className="wz-check">
            <input type="checkbox" checked={r.external} onChange={(e) => onChange(patchAt(rows, i, { external: e.target.checked }))} />
            <span>Already exists</span>
          </label>
          <button type="button" className="sk-btn sk-btn--plain" aria-label={`Remove ${singular.toLowerCase()} ${i + 1}`} onClick={() => onChange(remove(rows, i))}>
            Remove
          </button>
        </div>
      ))}
      <button type="button" className="sk-btn" onClick={() => onChange([...rows, { name: '', driver: '', external: false }])}>
        Add {singular.toLowerCase()}
      </button>
    </section>
  )
}

function ServiceCard({
  index,
  service,
  state,
  fields,
  onChange,
  onRemove,
}: {
  index: number
  service: ServiceForm
  state: WizardState
  fields: Map<string, string>
  onChange: (patch: Partial<ServiceForm>) => void
  onRemove: () => void
}) {
  const p = `services.${index}`
  const n = index + 1
  const err = (k: string) => fields.get(`${p}.${k}`)
  const others = state.services.filter((_, i) => i !== index).map((s) => s.name.trim()).filter(Boolean)
  const networkNames = state.networks.map((x) => x.name.trim()).filter(Boolean)
  return (
    <fieldset className="wz-card" aria-label={`Service ${n}`}>
      <div className="wz-card__head">
        <h3 className="wz-section__title">Service {n}</h3>
        {state.services.length > 1 && (
          <button type="button" className="sk-btn sk-btn--plain sk-btn--danger-text" aria-label={`Remove service ${n}`} onClick={onRemove}>
            Remove
          </button>
        )}
      </div>
      <div className="wz-row wz-row--two">
        <Field label="Service name" error={err('name')}>
          <input aria-label={`Service ${n} name`} value={service.name} onChange={(e) => onChange({ name: e.target.value })} autoComplete="off" spellCheck={false} />
        </Field>
        <Field label="Image" error={err('image')}>
          <input aria-label={`Service ${n} image`} value={service.image} placeholder="nginx:1.27" onChange={(e) => onChange({ image: e.target.value })} autoComplete="off" spellCheck={false} />
        </Field>
      </div>
      <div className="wz-row wz-row--two">
        <Field label="Command (optional)">
          <input aria-label={`Service ${n} command`} value={service.command} onChange={(e) => onChange({ command: e.target.value })} autoComplete="off" spellCheck={false} />
        </Field>
        <Field label="Restart">
          <select aria-label={`Service ${n} restart`} value={service.restart} onChange={(e) => onChange({ restart: e.target.value })}>
            <option value="">Default</option>
            {RESTART_POLICIES.map((r) => (
              <option key={r} value={r}>
                {r}
              </option>
            ))}
          </select>
        </Field>
      </div>

      <fieldset className="wz-group">
        <legend>Ports</legend>
        {service.ports.map((r, j) => (
          <div className="wz-row wz-row--port" key={j}>
            <Field label="Host" error={err(`ports.${j}.host`)}>
              <input aria-label={`Service ${n} port ${j + 1} host`} inputMode="numeric" placeholder="any" value={r.host} onChange={(e) => onChange({ ports: patchAt(service.ports, j, { host: e.target.value }) })} />
            </Field>
            <Field label="Container" error={err(`ports.${j}.container`)}>
              <input aria-label={`Service ${n} port ${j + 1} container`} inputMode="numeric" value={r.container} onChange={(e) => onChange({ ports: patchAt(service.ports, j, { container: e.target.value }) })} />
            </Field>
            <Field label="Protocol">
              <select aria-label={`Service ${n} port ${j + 1} protocol`} value={r.protocol} onChange={(e) => onChange({ ports: patchAt(service.ports, j, { protocol: e.target.value as 'tcp' | 'udp' }) })}>
                <option value="tcp">tcp</option>
                <option value="udp">udp</option>
              </select>
            </Field>
            <button type="button" className="sk-btn sk-btn--plain" aria-label={`Remove service ${n} port ${j + 1}`} onClick={() => onChange({ ports: remove(service.ports, j) })}>
              Remove
            </button>
          </div>
        ))}
        <button type="button" className="sk-btn" onClick={() => onChange({ ports: [...service.ports, { host: '', container: '', protocol: 'tcp' }] })}>
          Add port to service {n}
        </button>
      </fieldset>

      <fieldset className="wz-group">
        <legend>Environment</legend>
        {service.env.map((r, j) => (
          <div className="wz-row wz-row--env" key={j}>
            <Field label="Variable name" error={err(`env.${j}.key`)}>
              <input aria-label={`Service ${n} env ${j + 1} name`} value={r.key} placeholder="NAME" onChange={(e) => onChange({ env: patchAt(service.env, j, { key: e.target.value }) })} autoComplete="off" spellCheck={false} />
            </Field>
            <Field label={r.ref ? 'Stack variable' : 'Value'} error={err(`env.${j}.value`)}>
              <input aria-label={`Service ${n} env ${j + 1} ${r.ref ? 'reference' : 'value'}`} value={r.value} onChange={(e) => onChange({ env: patchAt(service.env, j, { value: e.target.value }) })} autoComplete="off" spellCheck={false} />
            </Field>
            <label className="wz-check">
              <input type="checkbox" checked={r.ref} onChange={(e) => onChange({ env: patchAt(service.env, j, { ref: e.target.checked }) })} />
              <span>From stack variable</span>
            </label>
            <button type="button" className="sk-btn sk-btn--plain" aria-label={`Remove service ${n} env ${j + 1}`} onClick={() => onChange({ env: remove(service.env, j) })}>
              Remove
            </button>
          </div>
        ))}
        <button type="button" className="sk-btn" onClick={() => onChange({ env: [...service.env, { key: '', value: '', ref: false }] })}>
          Add env var to service {n}
        </button>
      </fieldset>

      <fieldset className="wz-group">
        <legend>Volumes</legend>
        {service.volumes.map((r, j) => (
          <div className="wz-row wz-row--env" key={j}>
            <Field label="Volume or path" error={err(`volumes.${j}.source`)}>
              <input aria-label={`Service ${n} mount ${j + 1} source`} value={r.source} placeholder="data or ./data" onChange={(e) => onChange({ volumes: patchAt(service.volumes, j, { source: e.target.value }) })} autoComplete="off" spellCheck={false} />
            </Field>
            <Field label="Path in container" error={err(`volumes.${j}.target`)}>
              <input aria-label={`Service ${n} mount ${j + 1} target`} value={r.target} placeholder="/data" onChange={(e) => onChange({ volumes: patchAt(service.volumes, j, { target: e.target.value }) })} autoComplete="off" spellCheck={false} />
            </Field>
            <label className="wz-check">
              <input type="checkbox" checked={r.readOnly} onChange={(e) => onChange({ volumes: patchAt(service.volumes, j, { readOnly: e.target.checked }) })} />
              <span>Read only</span>
            </label>
            <button type="button" className="sk-btn sk-btn--plain" aria-label={`Remove service ${n} mount ${j + 1}`} onClick={() => onChange({ volumes: remove(service.volumes, j) })}>
              Remove
            </button>
          </div>
        ))}
        <button type="button" className="sk-btn" onClick={() => onChange({ volumes: [...service.volumes, { source: '', target: '', readOnly: false }] })}>
          Add volume to service {n}
        </button>
      </fieldset>

      <Toggle name={`${p}-networks`} label="Networks" options={networkNames} selected={service.networks} onChange={(networks) => onChange({ networks })} />
      <Toggle name={`${p}-depends`} label="Starts after" options={others} selected={service.dependsOn} onChange={(dependsOn) => onChange({ dependsOn })} />
    </fieldset>
  )
}

/**
 * Form wizard for a Compose file. One-way: the form generates YAML on the server, it never reads YAML back.
 * `onContinue` receives the YAML and the stack variables the form references (to be filled in the env editor).
 */
export function ComposeWizard({ onContinue }: { onContinue: (yaml: string, envKeys: string[]) => void }) {
  const previewId = useId()
  const generate = useGenerateCompose()
  const [state, setState] = useState<WizardState>(initialState)
  const [yaml, setYaml] = useState<string | null>(null)
  const [shown, setShown] = useState(false)
  const [problems, setProblems] = useState<string[]>([])
  const validation = validateWizard(state)

  function change(next: WizardState) {
    setState(next)
    setYaml(null) // the preview no longer matches the form
    generate.reset()
  }
  const setService = (i: number, patch: Partial<ServiceForm>) => change({ ...state, services: patchAt(state.services, i, patch) })

  function preview() {
    setShown(true)
    setProblems([])
    if (hasProblems(validation)) return
    generate.mutate(buildSpec(state), {
      onSuccess: (r) => setYaml(r.yaml),
      onError: (err) => {
        if (err instanceof ApiError && err.code === 'INVALID_COMPOSE_SPEC') setProblems(err.details?.length ? err.details : ['The stack is not valid.'])
        else setProblems(['Could not generate the Compose file. Try again.'])
      },
    })
  }

  const fields = shown ? validation.fields : new Map<string, string>()
  return (
    <div className="wz">
      {state.services.map((s, i) => (
        <ServiceCard
          key={s.uid}
          index={i}
          service={s}
          state={state}
          fields={fields}
          onChange={(patch) => setService(i, patch)}
          onRemove={() => change({ ...state, services: remove(state.services, i) })}
        />
      ))}
      <button type="button" className="sk-btn wz-add" onClick={() => change({ ...state, services: [...state.services, emptyService()] })}>
        Add service
      </button>
      <ResourceList kind="networks" title="Networks" rows={state.networks} fields={fields} onChange={(networks) => change({ ...state, networks })} />
      <ResourceList kind="volumes" title="Volumes" rows={state.volumes} fields={fields} onChange={(volumes) => change({ ...state, volumes })} />

      {shown && validation.general.length > 0 && (
        <p role="alert" className="sk-error">
          {validation.general.join(' ')}
        </p>
      )}
      {shown && validation.fields.size > 0 && (
        <p role="alert" className="sk-error">
          Fix the highlighted fields first.
        </p>
      )}
      {problems.length > 0 && (
        <div role="alert" className="sk-error">
          <p>The stack is not valid.</p>
          <ul className="wz-list">
            {problems.map((m, i) => (
              <li key={i}>{m}</li>
            ))}
          </ul>
        </div>
      )}

      <div className="wz-actions">
        <button type="button" className="sk-btn" onClick={preview} disabled={generate.isPending}>
          Preview YAML
        </button>
      </div>

      {yaml !== null && (
        <section className="wz-preview" aria-labelledby={previewId}>
          <h3 id={previewId} className="wz-section__title">
            Generated Compose file
          </h3>
          <YamlEditor label="Generated Compose file" value={yaml} readOnly height="220px" />
          <div className="wz-actions">
            <button type="button" className="sk-btn sk-btn--primary" onClick={() => onContinue(yaml, referencedEnvKeys(state))}>
              Continue in editor
            </button>
          </div>
        </section>
      )}
    </div>
  )
}
