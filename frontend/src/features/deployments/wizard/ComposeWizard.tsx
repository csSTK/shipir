import { useEffect, useId, useRef, useState, type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { ApiError } from '../../../shared/api/client.ts'
import { useDetectedProxy, useGenerateCompose, useManagedProxyType, useTraefikTemplate, type DetectedProxy } from '../api.ts'
import { YamlEditor } from '../editor/index.ts'
import {
  buildSpec,
  emptyService,
  hasProblems,
  initialState,
  PROXY_TYPES,
  type ProxyType,
  referencedEnvKeys,
  RESTART_POLICIES,
  validateWizard,
  type ResourceForm,
  type ServiceForm,
  type WizardState,
} from './model.ts'
import './wizard.css'
import { Checkbox, Button, Select, Switch, TextField } from '../../../shared/ui/index.ts'
import { useTranslation } from 'react-i18next'

function remove<T>(list: T[], index: number): T[] {
  return list.filter((_, i) => i !== index)
}
function patchAt<T>(list: T[], index: number, patch: Partial<T>): T[] {
  return list.map((item, i) => (i === index ? { ...item, ...patch } : item))
}

// Visual column labels for a list of rows; the inputs keep their own per-row accessible names, so this is hidden from assistive tech.
function ColumnHeads({ labels }: { labels: string[] }) {
  return (
    <div className="wz-head" aria-hidden="true">
      {labels.map((l, i) => (
        <span key={i}>{l}</span>
      ))}
    </div>
  )
}

// Wraps one input of a row so it can show its column label when rows stack (below 600px); a plain pass-through on wide screens.
function Cell({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="wz-cell" data-label={label}>
      {children}
    </div>
  )
}

function Toggle({ name, label, options, selected, onChange }: { name: string; label: string; options: string[]; selected: string[]; onChange: (next: string[]) => void }) {
  if (options.length === 0) return null
  return (
    <fieldset className="wz-group">
      <legend>{label}</legend>
      <div className="wz-chips">
        {options.map((o) => (
          <Checkbox
            key={o}
            className="wz-chip"
            label={o}
            name={name}
            checked={selected.includes(o)}
            onChange={(e) => onChange(e.target.checked ? [...selected, o] : selected.filter((x) => x !== o))}
          />
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
  const { t } = useTranslation('deployments')
  const singular = kind === 'networks' ? t('ComposeWizard.network') : t('ComposeWizard.volume')
  const lowerCase = kind === 'networks' ? t('ComposeWizard.networkLowercase') : t('ComposeWizard.volumeLowercase')
  return (
    <section className="wz-section" aria-label={title}>
      <h3 className="wz-section__title">{title}</h3>
      {rows.length === 0 && <p className="wz-hint">{t('ComposeWizard.noneDeclared')}</p>}
      <div className="wz-table wz-table--env">
      {rows.length > 0 && <ColumnHeads labels={[t('ComposeWizard.singularName', { singular }), t('ComposeWizard.driverOptional')]} />}
      {rows.map((r, i) => (
        <div className="wz-row" key={i}>
          <Cell label={t('ComposeWizard.singularName', { singular })}>
            <TextField label={t('ComposeWizard.singularValueName', { singular, value: i + 1 })} hideLabel error={fields.get(`${kind}.${i}.name`)} placeholder={t('ComposeWizard.singularName', { singular })} value={r.name} onChange={(e) => onChange(patchAt(rows, i, { name: e.target.value }))} autoComplete="off" spellCheck={false} />
          </Cell>
          <Cell label={t('ComposeWizard.driverOptional')}>
            <TextField label={t('ComposeWizard.singularValueDriver', { singular, value: i + 1 })} hideLabel error={fields.get(`${kind}.${i}.driver`)} placeholder={t('ComposeWizard.driverOptional')} value={r.driver} onChange={(e) => onChange(patchAt(rows, i, { driver: e.target.value }))} autoComplete="off" spellCheck={false} />
          </Cell>
          <div className="wz-toggle">
            <Switch label={t('ComposeWizard.alreadyExists')} checked={r.external} onChange={(e) => onChange(patchAt(rows, i, { external: e.target.checked }))} />
          </div>
          <Button variant="ghost" aria-label={t('ComposeWizard.removeLowercaseValue', { lowerCase, value: i + 1 })} onClick={() => onChange(remove(rows, i))}>
            {t('ComposeWizard.remove')}
          </Button>
        </div>
      ))}
      </div>
      <Button variant="secondary" onClick={() => onChange([...rows, { name: '', driver: '', external: false }])}>
        {t('ComposeWizard.addLowercase', { lowerCase })}
      </Button>
    </section>
  )
}

// Defaults of Shipir's Traefik template; the backend uses the same when the fields are empty.
const DEFAULT_ENTRYPOINT = 'websecure'
const DEFAULT_CERT_RESOLVER = 'letsencrypt'

function DomainSection({
  n,
  hostId,
  service,
  fields,
  detected,
  managedCaddy,
  onChange,
}: {
  hostId: string
  n: number
  service: ServiceForm
  fields: Map<string, string>
  detected: DetectedProxy | undefined
  /** The host runs Shipir's managed Caddy, which ignores Docker labels: Caddy labels are not offered. */
  managedCaddy: boolean
  onChange: (patch: Partial<ServiceForm>) => void
}) {
  const { t } = useTranslation('deployments')
  const template = useTraefikTemplate()
  const [copy, setCopy] = useState<'idle' | 'copied' | 'failed'>('idle')
  const d = service.domain
  const err = (k: string) => fields.get(`services.${n - 1}.domain.${k}`)
  // Fields the user edited themselves are never overwritten by the prefill.
  const touched = useRef(new Set<string>())
  const set = (patch: Partial<ServiceForm['domain']>) => onChange({ domain: { ...d, ...patch } })
  const setOwn = (patch: Partial<ServiceForm['domain']>) => {
    for (const k of Object.keys(patch)) touched.current.add(k)
    set(patch)
  }

  // A Caddy choice made before the managed proxy was known (or prefilled) would only be rejected by the server.
  useEffect(() => {
    if (managedCaddy && d.proxy === 'CADDY') onChange({ domain: { ...d, proxy: 'TRAEFIK' } })
  }, [managedCaddy, d, onChange])

  // The first time a host is typed, untouched proxy, network and port take what is known.
  function setHost(host: string) {
    if (d.host.trim() === '' && host.trim() !== '') {
      const own = touched.current
      set({
        host,
        proxy: own.has('proxy') ? d.proxy : (detected?.proxy ?? d.proxy),
        network: own.has('network') ? d.network : (detected?.network ?? d.network),
        port: own.has('port') ? d.port : d.port || (service.ports[0]?.container.trim() ?? ''),
      })
    } else set({ host })
  }

  function copyTemplate() {
    setCopy('idle')
    template.mutate(undefined, {
      onSuccess: async (r) => {
        try {
          // `navigator.clipboard` does not exist on non-secure contexts (plain http).
          await navigator.clipboard.writeText(r.yaml)
          setCopy('copied')
        } catch {
          setCopy('failed')
        }
      },
      onError: () => setCopy('failed'),
    })
  }

  return (
    <fieldset className="wz-group" aria-label={t('ComposeWizard.serviceNDomain', { n })}>
      <legend>{t('ComposeWizard.domain')}</legend>
      {detected?.proxy ? (
        <p className="wz-hint" role="status">
          {t('ComposeWizard.proxyDetected', {
            proxy: t(`ComposeWizard.proxyName${detected.proxy}`),
            container: detected.container ?? t('ComposeWizard.container').toLowerCase(),
          })}
          {detected.network ? ` ${t('ComposeWizard.onNetwork', { network: detected.network })}` : ''}. {t('ComposeWizard.fillInADomain')}
        </p>
      ) : detected && !detected.visible ? (
        <p className="wz-hint" role="status">{t('ComposeWizard.proxyDetectionNotAvailable')}</p>
      ) : (
        detected &&
        !managedCaddy && (
          <div className="wz-detect">
            <p className="wz-hint" role="status">{t('ComposeWizard.noReverseProxyDetected')}</p>
            <p className="wz-hint">{t('ComposeWizard.traefikTemplateHint')}</p>
            <Button variant="secondary" onClick={copyTemplate} disabled={template.isPending}>
              {t('ComposeWizard.copyTraefikTemplate')}
            </Button>
            {/* The live region is in the DOM before the message, so screen readers announce the change. */}
            <span role="status">
              {copy === 'copied' && <small className="wz-detect__ok">{t('ComposeWizard.copied')}</small>}
              {copy === 'failed' && <small className="wz-detect__fail">{t('ComposeWizard.couldNotCopyTheTemplate')}</small>}
            </span>
          </div>
        )
      )}
      {managedCaddy && (
        <p className="wz-hint" role="status">
          {t('ComposeWizard.managedCaddyHint')} <Link to={`/environments/${hostId}/domains`}>{t('ComposeWizard.openDomains')}</Link>
        </p>
      )}
      <div className="wz-row wz-row--domain">
        <TextField label={t('ComposeWizard.serviceNDomainHost', { n })} error={err('host')} value={d.host} placeholder="app.example.com" onChange={(e) => setHost(e.target.value)} autoComplete="off" spellCheck={false} />
        <TextField label={t('ComposeWizard.serviceNDomainPort', { n })} error={err('port')} inputMode="numeric" value={d.port} onChange={(e) => setOwn({ port: e.target.value })} />
        <Select label={t('ComposeWizard.serviceNDomainProxy', { n })} value={d.proxy} onChange={(e) => setOwn({ proxy: e.target.value as ProxyType })}>
          {PROXY_TYPES.filter((p) => !(managedCaddy && p === 'CADDY')).map((p) => (
            <option key={p} value={p}>
              {t(`ComposeWizard.proxyName${p}`)}
            </option>
          ))}
        </Select>
        <TextField label={t('ComposeWizard.serviceNDomainNetwork', { n })} error={err('network')} placeholder={t('ComposeWizard.proxyNetworkOptional')} value={d.network} onChange={(e) => setOwn({ network: e.target.value })} autoComplete="off" spellCheck={false} />
      </div>
      {d.proxy === 'TRAEFIK' && (
        <div className="wz-row wz-row--two">
          <TextField label={t('ComposeWizard.serviceNDomainEntrypoint', { n })} error={err('entrypoint')} value={d.entrypoint} placeholder={DEFAULT_ENTRYPOINT} onChange={(e) => setOwn({ entrypoint: e.target.value })} autoComplete="off" spellCheck={false} />
          <TextField label={t('ComposeWizard.serviceNDomainCertResolver', { n })} error={err('certResolver')} value={d.certResolver} placeholder={DEFAULT_CERT_RESOLVER} onChange={(e) => setOwn({ certResolver: e.target.value })} autoComplete="off" spellCheck={false} />
        </div>
      )}
      {d.proxy === 'TRAEFIK' && <small className="wz-hint">{t('ComposeWizard.traefikNamesHint')}</small>}
    </fieldset>
  )
}

function ServiceCard({
  hostId,
  managedCaddy,
  index,
  service,
  state,
  fields,
  detected,
  onChange,
  onRemove,
}: {
  hostId: string
  managedCaddy: boolean
  index: number
  service: ServiceForm
  state: WizardState
  fields: Map<string, string>
  detected: DetectedProxy | undefined
  onChange: (patch: Partial<ServiceForm>) => void
  onRemove: () => void
}) {
  const { t } = useTranslation('deployments')
  const p = `services.${index}`
  const n = index + 1
  const err = (k: string) => fields.get(`${p}.${k}`)
  const others = state.services.filter((_, i) => i !== index).map((s) => s.name.trim()).filter(Boolean)
  const networkNames = state.networks.map((x) => x.name.trim()).filter(Boolean)
  // Essentials (name, image, command, restart) stay visible; the rest folds away unless it already holds something.
  const hasAdvancedContent =
    service.ports.length > 0 || service.env.length > 0 || service.volumes.length > 0 || service.networks.length > 0 || service.dependsOn.length > 0 || service.domain.host.trim() !== ''
  const hasAdvancedErrors = [...fields.keys()].some((k) => /^services\.\d+\./.test(k) && k.startsWith(p + '.') && !/\.(name|image)$/.test(k))
  const [advancedOpen, setAdvancedOpen] = useState(hasAdvancedContent)
  return (
    <fieldset className="wz-card" aria-label={t('ComposeWizard.serviceN', { n })}>
      <div className="wz-card__head">
        <h3 className="wz-section__title">{t('ComposeWizard.serviceN', { n })}</h3>
        {state.services.length > 1 && (
          <Button variant="danger" aria-label={t('ComposeWizard.removeServiceN', { n })} onClick={onRemove}>
            {t('ComposeWizard.remove')}
          </Button>
        )}
      </div>
      <div className="wz-row wz-row--two">
        <TextField label={t('ComposeWizard.serviceNName', { n })} error={err('name')} value={service.name} onChange={(e) => onChange({ name: e.target.value })} autoComplete="off" spellCheck={false} />
        <TextField label={t('ComposeWizard.serviceNImage', { n })} error={err('image')} value={service.image} placeholder="nginx:1.27" onChange={(e) => onChange({ image: e.target.value })} autoComplete="off" spellCheck={false} />
      </div>
      <div className="wz-row wz-row--two">
        <TextField label={t('ComposeWizard.serviceNCommand', { n })} placeholder={t('ComposeWizard.commandOptional')} value={service.command} onChange={(e) => onChange({ command: e.target.value })} autoComplete="off" spellCheck={false} />
        <Select label={t('ComposeWizard.serviceNRestart', { n })} value={service.restart} onChange={(e) => onChange({ restart: e.target.value })}>
          <option value="">{t('ComposeWizard.default')}</option>
          {RESTART_POLICIES.map((r) => (
            <option key={r} value={r}>
              {r}
            </option>
          ))}
        </Select>
      </div>

      <details className="wz-advanced" open={advancedOpen || hasAdvancedErrors} onToggle={(e) => setAdvancedOpen(e.currentTarget.open)}>
        <summary className="wz-advanced__summary">{t('ComposeWizard.advancedOptions')}</summary>
        <div className="wz-advanced__body">
      <fieldset className="wz-group">
        <legend>{t('ComposeWizard.ports')}</legend>
        <div className="wz-table wz-table--port">
        {service.ports.length > 0 && <ColumnHeads labels={[t('ComposeWizard.host'), t('ComposeWizard.containerPort'), t('ComposeWizard.protocol')]} />}
        {service.ports.map((r, j) => (
          <div className="wz-row" key={j}>
            <Cell label={t('ComposeWizard.host')}>
              <TextField label={t('ComposeWizard.serviceNPortValueHost', { n, value: j + 1 })} hideLabel error={err(`ports.${j}.host`)} inputMode="numeric" placeholder={t('ComposeWizard.any')} value={r.host} onChange={(e) => onChange({ ports: patchAt(service.ports, j, { host: e.target.value }) })} />
            </Cell>
            <Cell label={t('ComposeWizard.containerPort')}>
              <TextField label={t('ComposeWizard.serviceNPortValueContainer', { n, value: j + 1 })} hideLabel error={err(`ports.${j}.container`)} inputMode="numeric" placeholder={t('ComposeWizard.container')} value={r.container} onChange={(e) => onChange({ ports: patchAt(service.ports, j, { container: e.target.value }) })} />
            </Cell>
            <Cell label={t('ComposeWizard.protocol')}>
              <Select label={t('ComposeWizard.serviceNPortValueProtocol', { n, value: j + 1 })} hideLabel value={r.protocol} onChange={(e) => onChange({ ports: patchAt(service.ports, j, { protocol: e.target.value as 'tcp' | 'udp' }) })}>
                <option value="tcp">{t('ComposeWizard.tcp')}</option>
                <option value="udp">{t('ComposeWizard.udp')}</option>
              </Select>
            </Cell>
            <Button variant="ghost" aria-label={t('ComposeWizard.removeServiceNPortValue', { n, value: j + 1 })} onClick={() => onChange({ ports: remove(service.ports, j) })}>
              {t('ComposeWizard.remove')}
            </Button>
          </div>
        ))}
        </div>
        <Button variant="secondary" onClick={() => onChange({ ports: [...service.ports, { host: '', container: '', protocol: 'tcp' }] })}>
          {t('ComposeWizard.addPortToServiceN', { n })}
        </Button>
      </fieldset>

      <fieldset className="wz-group">
        <legend>{t('ComposeWizard.environment')}</legend>
        <div className="wz-table wz-table--env">
        {service.env.length > 0 && <ColumnHeads labels={[t('ComposeWizard.variableName'), t('ComposeWizard.value')]} />}
        {service.env.map((r, j) => (
          <div className="wz-row" key={j}>
            <Cell label={t('ComposeWizard.variableName')}>
              <TextField label={t('ComposeWizard.serviceNEnvValueName', { n, value: j + 1 })} hideLabel error={err(`env.${j}.key`)} value={r.key} placeholder={t('ComposeWizard.name')} onChange={(e) => onChange({ env: patchAt(service.env, j, { key: e.target.value }) })} autoComplete="off" spellCheck={false} />
            </Cell>
            <Cell label={t('ComposeWizard.value')}>
              <TextField label={r.ref ? t('ComposeWizard.serviceNEnvValueReference', { n, value: j + 1 }) : t('ComposeWizard.serviceNEnvValueValue', { n, value: j + 1 })} hideLabel error={err(`env.${j}.value`)} placeholder={r.ref ? t('ComposeWizard.deploymentVariable') : t('ComposeWizard.value')} value={r.value} onChange={(e) => onChange({ env: patchAt(service.env, j, { value: e.target.value }) })} autoComplete="off" spellCheck={false} />
            </Cell>
            <div className="wz-toggle">
              <Switch label={t('ComposeWizard.fromDeploymentVariable')} checked={r.ref} onChange={(e) => onChange({ env: patchAt(service.env, j, { ref: e.target.checked }) })} />
            </div>
            <Button variant="ghost" aria-label={t('ComposeWizard.removeServiceNEnvValue', { n, value: j + 1 })} onClick={() => onChange({ env: remove(service.env, j) })}>
              {t('ComposeWizard.remove')}
            </Button>
          </div>
        ))}
        </div>
        <Button variant="secondary" onClick={() => onChange({ env: [...service.env, { key: '', value: '', ref: false }] })}>
          {t('ComposeWizard.addEnvVarToService', { n })}
        </Button>
      </fieldset>

      <fieldset className="wz-group">
        <legend>{t('ComposeWizard.volumes')}</legend>
        <div className="wz-table wz-table--env">
        {service.volumes.length > 0 && <ColumnHeads labels={[t('ComposeWizard.volumeOrPath'), t('ComposeWizard.pathInContainer')]} />}
        {service.volumes.map((r, j) => (
          <div className="wz-row" key={j}>
            <Cell label={t('ComposeWizard.volumeOrPath')}>
              <TextField label={t('ComposeWizard.serviceNMountValueSource', { n, value: j + 1 })} hideLabel error={err(`volumes.${j}.source`)} value={r.source} placeholder={t('ComposeWizard.dataOrData')} onChange={(e) => onChange({ volumes: patchAt(service.volumes, j, { source: e.target.value }) })} autoComplete="off" spellCheck={false} />
            </Cell>
            <Cell label={t('ComposeWizard.pathInContainer')}>
              <TextField label={t('ComposeWizard.serviceNMountValueTarget', { n, value: j + 1 })} hideLabel error={err(`volumes.${j}.target`)} value={r.target} placeholder="/data" onChange={(e) => onChange({ volumes: patchAt(service.volumes, j, { target: e.target.value }) })} autoComplete="off" spellCheck={false} />
            </Cell>
            <div className="wz-toggle">
              <Switch label={t('ComposeWizard.readOnly')} checked={r.readOnly} onChange={(e) => onChange({ volumes: patchAt(service.volumes, j, { readOnly: e.target.checked }) })} />
            </div>
            <Button variant="ghost" aria-label={t('ComposeWizard.removeServiceNMountValue', { n, value: j + 1 })} onClick={() => onChange({ volumes: remove(service.volumes, j) })}>
              {t('ComposeWizard.remove')}
            </Button>
          </div>
        ))}
        </div>
        <Button variant="secondary" onClick={() => onChange({ volumes: [...service.volumes, { source: '', target: '', readOnly: false }] })}>
          {t('ComposeWizard.addVolumeToServiceN', { n })}
        </Button>
      </fieldset>

      <DomainSection n={n} hostId={hostId} service={service} fields={fields} detected={detected} managedCaddy={managedCaddy} onChange={onChange} />

      <Toggle name={`${p}-networks`} label={t('ComposeWizard.networks')} options={networkNames} selected={service.networks} onChange={(networks) => onChange({ networks })} />
      <Toggle name={`${p}-depends`} label={t('ComposeWizard.startsAfter')} options={others} selected={service.dependsOn} onChange={(dependsOn) => onChange({ dependsOn })} />
        </div>
      </details>
    </fieldset>
  )
}

/**
 * Form wizard for a Compose file. One-way: the form generates YAML on the server, it never reads YAML back.
 * `initial` seeds the form once (remount with a new `key` to reseed, e.g. from a template).
 * `onContinue` receives the YAML and the deployment variables the form references (to be filled in the env editor).
 */
export function ComposeWizard({
  hostId,
  active = true,
  onContinue,
  initial,
}: {
  hostId: string
  /** False while the wizard is mounted but hidden: proxy detection is then not requested. */
  active?: boolean
  onContinue: (yaml: string, envKeys: string[]) => void
  initial?: WizardState
}) {
  const detected = useDetectedProxy(hostId, active).data
  const [state, setState] = useState<WizardState>(() => initial ?? initialState())
  // Asked once a domain is being entered, so wizards without a domain make no extra request.
  const managedCaddy =
    useManagedProxyType(hostId, active && state.services.some((s) => s.domain.host.trim() !== '')).data?.instance?.type === 'CADDY'
  const { t } = useTranslation('deployments')
  const previewId = useId()
  const generate = useGenerateCompose(hostId)
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
        if (err instanceof ApiError && err.code === 'INVALID_COMPOSE_SPEC') setProblems(err.details?.length ? err.details : [t('ComposeWizard.theDeploymentIsNotValid')])
        else setProblems([t('ComposeWizard.couldNotGenerateTheCompose')])
      },
    })
  }

  const fields = shown ? validation.fields : new Map<string, string>()
  return (
    <div className="wz">
      {state.services.map((s, i) => (
        <ServiceCard
          hostId={hostId}
          managedCaddy={managedCaddy}
          key={s.uid}
          index={i}
          service={s}
          state={state}
          fields={fields}
          detected={detected}
          onChange={(patch) => setService(i, patch)}
          onRemove={() => change({ ...state, services: remove(state.services, i) })}
        />
      ))}
      <Button variant="secondary" className="wz-add" onClick={() => change({ ...state, services: [...state.services, emptyService()] })}>
        {t('ComposeWizard.addService')}
      </Button>
      <ResourceList kind="networks" title={t('ComposeWizard.networks')} rows={state.networks} fields={fields} onChange={(networks) => change({ ...state, networks })} />
      <ResourceList kind="volumes" title={t('ComposeWizard.volumes')} rows={state.volumes} fields={fields} onChange={(volumes) => change({ ...state, volumes })} />

      {shown && validation.general.length > 0 && (
        <p role="alert" className="ui-error">
          {validation.general.join(' ')}
        </p>
      )}
      {shown && validation.fields.size > 0 && (
        <p role="alert" className="ui-error">
          {t('ComposeWizard.fixTheHighlightedFieldsFirst')}
        </p>
      )}
      {problems.length > 0 && (
        <div role="alert" className="ui-error">
          <p>{t('ComposeWizard.theDeploymentIsNotValid')}</p>
          <ul className="wz-list">
            {problems.map((m, i) => (
              <li key={i}>{m}</li>
            ))}
          </ul>
        </div>
      )}

      <div className="wz-actions">
        <Button variant="secondary" onClick={preview} disabled={generate.isPending}>
          {t('ComposeWizard.previewYaml')}
        </Button>
      </div>

      {yaml !== null && (
        <section className="wz-preview" aria-labelledby={previewId}>
          <h3 id={previewId} className="wz-section__title">
            {t('ComposeWizard.generatedComposeFile')}
          </h3>
          <YamlEditor label={t('ComposeWizard.generatedComposeFile')} value={yaml} readOnly height="220px" />
          <div className="wz-actions">
            <Button variant="primary" onClick={() => onContinue(yaml, referencedEnvKeys(state))}>
              {t('ComposeWizard.continueInEditor')}
            </Button>
          </div>
        </section>
      )}
    </div>
  )
}
