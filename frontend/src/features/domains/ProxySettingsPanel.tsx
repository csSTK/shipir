import { useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { Plus, X } from 'lucide-react'
import { Button, Card, Checkbox, IconButton, Select, TextField } from '../../shared/ui/index.ts'
import { DeployOutput } from '../deployments/deploy-output/DeployOutput.tsx'
import { useUpdateProxy, type CertMode, type ProxyInstance, type ProxyUpdateRequest } from './api.ts'
import { describeProxyError } from './domainErrors.ts'
import './ProxySettingsPanel.css'

interface Row {
  key: number
  name: string
  value: string
}

/** Technical example for the placeholder; not translated. */
const EXAMPLE_PROVIDER = 'cloudflare'
/** Mirror of the server's `ProxySetupRules`. */
const PROVIDER = /^[a-z0-9][a-z0-9-]{1,39}$/
const VARIABLE = /^[A-Z][A-Z0-9_]{0,63}$/
const RESERVED = new Set(['ACME_EMAIL', 'DNS_PROVIDER'])
const MAX_VARIABLES = 10
const MAX_VALUE = 2000
function validValue(value: string): boolean {
  if (value.length < 1 || value.length > MAX_VALUE) return false
  for (let i = 0; i < value.length; i++) {
    const c = value.charCodeAt(i)
    if (c < 32 || c === 127) return false
  }
  return true
}
let nextKey = 1
const emptyRow = (): Row => ({ key: nextKey++, name: '', value: '' })

/**
 * Advanced proxy settings for administrators: default certificate mode and, with Traefik, the DNS provider and its
 * credentials. Credentials are write-only: the server returns only a hint, so the fields start empty and entering new
 * ones replaces all stored ones.
 */
export function ProxySettingsPanel({ hostId, instance }: { hostId: string; instance: ProxyInstance }) {
  const { t } = useTranslation('domains')
  const update = useUpdateProxy(hostId)
  const traefik = instance.type === 'TRAEFIK'
  const stored = instance.dnsCredentialsHint !== null
  const [mode, setMode] = useState<CertMode>(instance.defaultCertMode)
  const [provider, setProvider] = useState(instance.dnsProvider ?? '')
  const [replacing, setReplacing] = useState(!stored)
  const [rows, setRows] = useState<Row[]>(() => [emptyRow()])
  const [remove, setRemove] = useState(false)
  const [problem, setProblem] = useState<string | null>(null)
  const [runId, setRunId] = useState<string | null>(null)
  const [saved, setSaved] = useState(false)

  const modes: CertMode[] = traefik ? ['ACME_HTTP', 'ACME_DNS', 'INTERNAL'] : ['ACME_HTTP', 'INTERNAL']
  const providerValue = provider.trim()

  const fail = (message: string) => setProblem(message)

  function submit(event: FormEvent) {
    event.preventDefault()
    setProblem(null)
    setSaved(false)
    const entered = replacing && !remove ? rows.filter((r) => r.name.trim()) : []
    const body: ProxyUpdateRequest = {}
    if (mode !== instance.defaultCertMode) body.defaultCertMode = mode
    const providerChanged = traefik && !remove && providerValue !== (instance.dnsProvider ?? '')
    if (providerChanged || (entered.length > 0 && !providerValue)) {
      if (!providerValue) return fail(t('ProxySettingsPanel.providerRequired'))
      if (!PROVIDER.test(providerValue)) return fail(t('ProxySettingsPanel.providerInvalid'))
    }
    if (providerChanged) body.dnsProvider = providerValue
    if (entered.length > MAX_VARIABLES) return fail(t('ProxySettingsPanel.tooManyVariables'))
    if (entered.some((r) => !VARIABLE.test(r.name.trim()) || RESERVED.has(r.name.trim()))) return fail(t('ProxySettingsPanel.variableInvalid'))
    if (entered.some((r) => !validValue(r.value))) return fail(t('ProxySettingsPanel.valueInvalid'))
    if (entered.length > 0) body.dnsCredentials = Object.fromEntries(entered.map((r) => [r.name.trim(), r.value]))
    if (remove) body.removeDnsCredentials = true
    if (body.dnsProvider !== undefined && !body.dnsCredentials) {
      return fail(stored && !replacing ? t('ProxySettingsPanel.replaceFirst') : t('ProxySettingsPanel.credentialsRequired'))
    }
    if (Object.keys(body).length === 0) return fail(t('ProxySettingsPanel.nothingChanged'))
    update.mutate(body, {
      onSuccess: (result) => {
        update.reset()
        setRows([emptyRow()])
        setRemove(false)
        setReplacing(false)
        setRunId(result.runId)
        setSaved(true)
      },
      onError: (error) => {
        // The request holds the credential values; the form keeps the input for a retry.
        update.reset()
        setProblem(describeProxyError(error))
      },
    })
  }

  const setRow = (key: number, patch: Partial<Row>) => setRows((current) => current.map((r) => (r.key === key ? { ...r, ...patch } : r)))

  return (
    <Card className="proxy-settings" id="proxy-settings">
      <h2 className="proxy-settings__title">{t('ProxySettingsPanel.title')}</h2>
      <p className="proxy-settings__intro">{t('ProxySettingsPanel.intro')}</p>
      <form className="proxy-settings__form" onSubmit={submit} noValidate autoComplete="off">
        <Select label={t('ProxySettingsPanel.defaultMode')} help={t('ProxySettingsPanel.defaultModeHelp')} value={mode} onChange={(e) => setMode(e.target.value as CertMode)}>
          {modes.map((m) => (
            <option key={m} value={m}>
              {t(`PublishDialog.advanced.certMode_${m}`)}
            </option>
          ))}
        </Select>

        {traefik && (
          <fieldset className="proxy-settings__group">
            <legend className="proxy-settings__legend">{t('ProxySettingsPanel.dns')}</legend>
            <TextField label={t('ProxySettingsPanel.provider')} help={t('ProxySettingsPanel.providerHelp')} value={provider} placeholder={EXAMPLE_PROVIDER} autoCapitalize="off" spellCheck={false} onChange={(e) => setProvider(e.target.value)} />
            {stored && (
              <p className="proxy-settings__hint">
                {t('ProxySettingsPanel.stored', { hint: instance.dnsCredentialsHint })}
              </p>
            )}
            {stored && !replacing && (
              <div className="proxy-settings__buttons">
                <Button size="sm" variant="secondary" onClick={() => setReplacing(true)}>
                  {t('ProxySettingsPanel.replace')}
                </Button>
              </div>
            )}
            {replacing && !remove && (
              <>
                <p className="proxy-settings__hint">{t('ProxySettingsPanel.credentialsHelp')}</p>
                {rows.map((r) => (
                  <div key={r.key} className="proxy-settings__row">
                    <TextField hideLabel label={t('ProxySettingsPanel.variableName')} value={r.name} placeholder="CF_DNS_API_TOKEN" autoCapitalize="off" spellCheck={false} autoComplete="off" onChange={(e) => setRow(r.key, { name: e.target.value })} />
                    <TextField hideLabel label={t('ProxySettingsPanel.variableValue')} type="password" value={r.value} autoComplete="new-password" data-1p-ignore data-lpignore="true" onChange={(e) => setRow(r.key, { value: e.target.value })} />
                    <IconButton aria-label={t('ProxySettingsPanel.removeVariable', { name: r.name || '…' })} icon={X} onClick={() => setRows((c) => (c.length > 1 ? c.filter((x) => x.key !== r.key) : [emptyRow()]))} />
                  </div>
                ))}
                <div className="proxy-settings__buttons">
                  <Button size="sm" variant="secondary" onClick={() => setRows((c) => [...c, emptyRow()])}>
                    <Plus size={16} strokeWidth={1.75} aria-hidden="true" />
                    {t('ProxySettingsPanel.addVariable')}
                  </Button>
                </div>
              </>
            )}
            {stored && <Checkbox label={t('ProxySettingsPanel.remove')} checked={remove} onChange={(e) => setRemove(e.target.checked)} />}
          </fieldset>
        )}

        {problem && (
          <p role="alert" className="proxy-settings__error">
            {problem}
          </p>
        )}
        {saved && !problem && (
          <p role="status" className="proxy-settings__saved">
            {runId ? t('ProxySettingsPanel.savedRestart') : t('ProxySettingsPanel.saved')}
          </p>
        )}
        <div className="proxy-settings__actions">
          <Button type="submit" variant="primary" loading={update.isPending}>
            {t('ProxySettingsPanel.save')}
          </Button>
        </div>
      </form>
      {runId && <DeployOutput title={t('ProxySettingsPanel.runTitle')} runId={runId} />}
    </Card>
  )
}
