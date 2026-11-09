import { useEffect, useMemo, useRef, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import { Eye, EyeOff, RefreshCw } from 'lucide-react'
import { ApiError } from '../../../shared/api/client.ts'
import { Button, Dialog, IconButton, TextField } from '../../../shared/ui/index.ts'
import { useCreateDeployment, useDeployDeployment, useGenerateCompose, useManagedDeployments, type EnvEntry } from '../api.ts'
import { describeDeploymentError } from '../deploymentErrors.ts'
import { toPayload } from '../editor/index.ts'
import { applyFreePorts, preferredHostPorts, specToState, syncPortValue, type WizardState } from '../wizard/index.ts'
import { FIXED_HOST_PORTS, buildSpec, validatePort } from '../wizard/model.ts'
import { fetchFreePorts, type AppTemplate } from './api.ts'
import { generateSecret, initialEnv, suggestName, webTarget } from './install.ts'
import { InstallPublishStep } from './InstallPublishStep.tsx'
import { useCurrentUser } from '../../auth/index.ts'
import { useManagedProxy } from '../../domains/index.ts'
import './templates.css'

const NAME_PATTERN = /^[a-z0-9][a-z0-9_-]*$/

/** What the wizard needs to continue from the install dialog ("Advanced"). */
export interface InstallSeed {
  template: AppTemplate
  name: string
  state: WizardState
  env: EnvEntry[]
}

interface PortRow {
  /** Index of the service and of the port in `state.services`. */
  si: number
  pi: number
  service: string
  container: string
  value: string
}

function portRows(state: WizardState): PortRow[] {
  return state.services.flatMap((s, si) =>
    s.ports.flatMap((p, pi) => (p.host.trim() === '' ? [] : [{ si, pi, service: s.name, container: p.container, value: p.host }])),
  )
}

function withPorts(state: WizardState, rows: PortRow[]): WizardState {
  return {
    ...state,
    services: state.services.map((s, si) => ({
      ...s,
      ports: s.ports.map((p, pi) => ({ ...p, host: rows.find((r) => r.si === si && r.pi === pi)?.value.trim() ?? p.host })),
    })),
  }
}

/**
 * One-click install of a catalog app: name, host ports and variables are prefilled (free ports, defaults, generated secrets),
 * one confirm creates and deploys, then the deployment page shows the run output. "Advanced" continues in the wizard instead.
 */
export function InstallTemplateDialog({
  hostId,
  template,
  onClose,
  onAdvanced,
}: {
  hostId: string
  template: AppTemplate
  onClose: () => void
  onAdvanced: (seed: InstallSeed) => void
}) {
  const { t: tr } = useTranslation('deployments')
  const navigate = useNavigate()
  const existing = useManagedDeployments(hostId)
  const generate = useGenerateCompose(hostId)
  const create = useCreateDeployment(hostId)
  const deploy = useDeployDeployment(hostId)

  const baseState = useMemo(() => specToState(template.spec), [template])
  const [state, setState] = useState(baseState)
  const [ports, setPorts] = useState(() => portRows(baseState))
  const [env, setEnv] = useState(() => initialEnv(template, baseState))
  const [revealed, setRevealed] = useState<Set<string>>(new Set())
  const [name, setName] = useState(template.id)
  const nameTouched = useRef(false)
  const portsTouched = useRef(false)
  const [nameError, setNameError] = useState<string | null>(null)
  const [keptPorts, setKeptPorts] = useState<number[]>([])
  const [portErrors, setPortErrors] = useState<Record<string, string>>({})
  const [problem, setProblem] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [createdId, setCreatedId] = useState<string | null>(null)
  const [deployedId, setDeployedId] = useState<string | null>(null)
  const userQuery = useCurrentUser()
  const user = userQuery.data
  const proxy = useManagedProxy(hostId)
  const web = useMemo(() => webTarget(template), [template])

  // Suggest a name that is not taken yet, until the user types one.
  useEffect(() => {
    if (existing.data && !nameTouched.current) setName(suggestName(template.id, existing.data.map((d) => d.name)))
  }, [existing.data, template.id])

  // Replace clashing host ports by free ones, until the user edits a port. A failed lookup keeps the defaults.
  useEffect(() => {
    const preferred = preferredHostPorts(baseState)
    if (preferred.length === 0) return
    let cancelled = false
    fetchFreePorts(hostId, preferred)
      .then((suggestions) => {
        if (cancelled || portsTouched.current) return
        const { state: next, kept } = applyFreePorts(baseState, suggestions)
        setKeptPorts(kept)
        setState(next)
        setPorts(portRows(next))
      })
      .catch(() => {})
    return () => {
      cancelled = true
    }
  }, [hostId, baseState])

  // Once the deployment exists, the inputs are frozen; only the deploy is retried.
  const locked = busy || createdId !== null

  // After a successful deploy: offer publishing to operators once the proxy state is known, else continue to the deployment.
  const operator = user?.role === 'ADMIN' || user?.role === 'OPERATOR'
  type After = 'go' | { proxy: 'ready' | 'notRunning' | 'missing' }
  const [decided, setDecided] = useState<After | null>(null)
  let live: 'wait' | After | null = null
  if (deployedId !== null) {
    if (userQuery.isPending || (operator && web && proxy.isFetching && !proxy.data)) live = 'wait'
    else if (!operator || !web || !proxy.data) live = 'go'
    else live = { proxy: !proxy.data.instance ? 'missing' : proxy.data.instance.deploymentStatus === 'RUNNING' ? 'ready' : 'notRunning' }
  }
  // Latch the first resolved outcome: later refetches (tab focus, failures) must not tear down the step or an open publish dialog.
  if (live !== null && live !== 'wait' && decided === null) setDecided(live)
  const after = decided ?? live
  useEffect(() => {
    if (deployedId !== null && after === 'go') navigate(`/environments/${hostId}/deployments/${deployedId}`)
  }, [deployedId, after, hostId, navigate])

  function setPort(row: PortRow, value: string) {
    portsTouched.current = true
    setPorts((rows) => syncPortValue(rows, row, value))
    setKeptPorts((kept) => kept.filter((k) => String(k) !== row.value.trim()))
    setPortErrors({})
  }

  function setEnvValue(key: string, value: string) {
    setEnv((rows) => rows.map((r) => (r.key === key ? { ...r, value } : r)))
  }

  function toggleReveal(key: string) {
    setRevealed((current) => {
      const next = new Set(current)
      if (!next.delete(key)) next.add(key)
      return next
    })
  }

  function advanced() {
    onAdvanced({ template, name: name.trim(), state: withPorts(state, ports), env })
  }

  /** Validates name and ports; a port that is taken by now is flagged with the free suggestion. */
  async function checkInputs(): Promise<boolean> {
    const trimmed = name.trim()
    if (!NAME_PATTERN.test(trimmed) || trimmed.length > 63) {
      setNameError(tr('AddDeploymentDialog.useLowercaseLettersDigitsHyphens'))
      return false
    }
    const errors: Record<string, string> = {}
    for (const r of ports) {
      const message = validatePort(r.value, true)
      if (message) errors[`${r.si}:${r.pi}`] = message
    }
    if (Object.keys(errors).length === 0 && createdId === null && ports.length > 0) {
      const wanted = ports.map((r) => Number(r.value)).filter((p, i, all) => all.indexOf(p) === i)
      try {
        const suggestions = await fetchFreePorts(hostId, wanted)
        for (const r of ports) {
          const suggestion = suggestions.find((s) => s.preferred === Number(r.value))
          if (suggestion && suggestion.free !== Number(r.value) && !FIXED_HOST_PORTS.has(suggestion.preferred)) {
            errors[`${r.si}:${r.pi}`] =
              suggestion.free === null
                ? tr('InstallTemplateDialog.portTakenNoFree', { port: r.value })
                : tr('InstallTemplateDialog.portTakenTry', { port: r.value, free: suggestion.free })
          }
        }
      } catch {
        // The lookup is advisory; Docker reports a real clash in the run output.
      }
    }
    setPortErrors(errors)
    return Object.keys(errors).length === 0
  }

  async function install(e: FormEvent) {
    e.preventDefault()
    if (busy) return
    setProblem(null)
    setNameError(null)
    setBusy(true)
    try {
      if (!(await checkInputs())) return
      let id = createdId
      if (id === null) {
        let yaml: string
        try {
          yaml = (await generate.mutateAsync(buildSpec(withPorts(state, ports)))).yaml
        } catch (err) {
          const details = err instanceof ApiError ? (err.details ?? []) : []
          setProblem([tr('InstallTemplateDialog.couldNotBuildTheComposeFile'), ...details].join(' '))
          return
        }
        try {
          id = (await create.mutateAsync({ name: name.trim(), yaml, env: toPayload(env) })).id
          setCreatedId(id)
        } catch (err) {
          if (err instanceof ApiError && err.code === 'DEPLOYMENT_NAME_TAKEN') setNameError(describeDeploymentError(err, ''))
          else setProblem(describeDeploymentError(err, tr('AddDeploymentDialog.couldNotCreateTheDeployment')))
          return
        }
      }
      try {
        await deploy.mutateAsync(id)
      } catch (err) {
        setProblem(describeDeploymentError(err, tr('InstallTemplateDialog.couldNotStartTheDeployment')))
        return
      }
      // What happens next is decided in render, once the user and proxy state are known (they may still be loading here).
      // Fresh proxy state for the decision (a cached result may predate the deploy); a failed refetch just falls back to the cache.
      await proxy.refetch()
      setDeployedId(id)
    } finally {
      setBusy(false)
    }
  }

  if (deployedId !== null && after !== null && typeof after === 'object' && web) {
    return (
      <Dialog size="lg" open onOpenChange={(open) => !open && navigate(`/environments/${hostId}/deployments/${deployedId}`)} title={tr('InstallPublishStep.title', { name: template.name })}>
        <InstallPublishStep
          hostId={hostId}
          proxy={after.proxy}
          project={name.trim()}
          service={web.service}
          port={web.port}
          onContinue={() => navigate(`/environments/${hostId}/deployments/${deployedId}`)}
        />
      </Dialog>
    )
  }

  return (
    <Dialog size="lg" open dismissible={!busy} onOpenChange={(open) => !open && !busy && onClose()} title={tr('InstallTemplateDialog.installName', { name: template.name })} description={template.description}>
      <form onSubmit={(e) => void install(e)} className="ui-form">
        <TextField
          label={tr('AddDeploymentDialog.name')}
          value={name}
          onChange={(e) => {
            nameTouched.current = true
            setName(e.target.value)
            setNameError(null)
          }}
          error={nameError}
          disabled={locked}
          autoComplete="off"
          spellCheck={false}
        />
        {ports.map((r) => (
          <TextField
            key={`${r.si}:${r.pi}`}
            label={tr('InstallTemplateDialog.hostPortFor', { service: r.service, container: r.container })}
            type="number"
            inputMode="numeric"
            value={r.value === '' ? '' : Number(r.value)}
            onChange={(e) => setPort(r, e.target.value)}
            error={portErrors[`${r.si}:${r.pi}`]}
            help={keptPorts.some((k) => String(k) === r.value.trim()) ? tr('InstallTemplateDialog.fixedPortTaken', { port: r.value.trim() }) : undefined}
            disabled={locked}
          />
        ))}
        {env.map((row) => {
          const hint = template.env.find((h) => h.key === row.key)
          const secret = hint?.secret === true
          const shown = !secret || revealed.has(row.key)
          return (
            <div key={row.key} className="tp-install__var">
              <TextField
                label={row.key}
                help={hint?.description}
                type={shown ? 'text' : 'password'}
                value={row.value}
                onChange={(e) => setEnvValue(row.key, e.target.value)}
                disabled={locked}
                autoComplete="off"
                spellCheck={false}
              />
              {secret && (
                <div className="tp-install__tools">
                  <IconButton
                    icon={shown ? EyeOff : Eye}
                    aria-label={shown ? tr('InstallTemplateDialog.hideKey', { key: row.key }) : tr('InstallTemplateDialog.showKey', { key: row.key })}
                    aria-pressed={shown}
                    onClick={() => toggleReveal(row.key)}
                  />
                  {hint?.generate && (
                    <IconButton icon={RefreshCw} aria-label={tr('InstallTemplateDialog.generateNewKey', { key: row.key })} disabled={locked} onClick={() => setEnvValue(row.key, generateSecret())} />
                  )}
                </div>
              )}
            </div>
          )
        })}
        {problem && (
          <p role="alert" className="ui-error">
            {problem}
          </p>
        )}
        <div className="ui-dialog-actions">
          <Button variant="ghost" onClick={onClose} disabled={busy}>
            {tr('AddDeploymentDialog.cancel')}
          </Button>
          <Button variant="secondary" onClick={advanced} disabled={locked}>
            {tr('InstallTemplateDialog.advanced')}
          </Button>
          <Button variant="primary" type="submit" loading={busy || after === 'wait'}>
            {tr('InstallTemplateDialog.install')}
          </Button>
        </div>
      </form>
    </Dialog>
  )
}
