import { Plus, X } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { Button, IconButton, Select, Switch, TextField } from '../../../shared/ui/index.ts'
import type { CertMode, ProxyType } from '../api.ts'
import { useCertificates } from '../certificates/api.ts'
import { newKey, type AdvancedErrors, type AdvancedState } from './advancedState.ts'
import type { BackendScheme } from './api.ts'
import './AdvancedFields.css'

interface Props {
  hostId: string
  state: AdvancedState
  onChange: (patch: Partial<AdvancedState>) => void
  errors: AdvancedErrors
  /** Null while the proxy is still loading: options that only exist for one proxy stay hidden until it is known. */
  proxyType: ProxyType | null
  /** Offer "proxy default" as certificate choice (new routes). */
  allowDefaultMode: boolean
  /** The proxy has a DNS provider and stored credentials, so the DNS challenge can issue certificates. */
  dnsReady: boolean
  onManageCertificates: () => void
}

/** Technical example values for placeholders; they are not translated. */
const EXAMPLE = { entrypoint: 'websecure', headerName: 'X-Frame-Options', headerValue: 'DENY' }
const SCHEMES: BackendScheme[] = ['HTTP', 'HTTPS', 'H2C']

function Group({ legend, help, children }: { legend: string; help?: string; children: React.ReactNode }) {
  return (
    <fieldset className="advanced__group">
      <legend className="advanced__legend">{legend}</legend>
      {help && <p className="advanced__help">{help}</p>}
      {children}
    </fieldset>
  )
}

function Problem({ id, text }: { id?: string; text: string | null | undefined }) {
  return text ? (
    <p id={id} role="alert" className="ui-field__error">
      {text}
    </p>
  ) : null
}

/**
 * The advanced options of a route (aliases, path, backend protocol, entrypoint, certificate, basic auth, headers).
 * Controlled by the dialog, so hiding it (Advanced mode off) never loses what was typed. Passwords are write-only:
 * stored users come with an empty password field.
 */
export function AdvancedFields({ hostId, state, onChange, errors, proxyType, allowDefaultMode, dnsReady, onManageCertificates }: Props) {
  const { t } = useTranslation('domains')
  const certificates = useCertificates(hostId)
  const problem = (field: keyof AdvancedErrors) => (errors[field] ? t(`PublishDialog.advanced.problem.${errors[field]}`) : null)
  // Without DNS credentials the server rejects the DNS challenge; a route that already uses it keeps the option visible.
  const offerDns = proxyType === 'TRAEFIK' && (dnsReady || state.certMode === 'ACME_DNS')
  const modes: CertMode[] = offerDns ? ['ACME_HTTP', 'ACME_DNS', 'INTERNAL', 'CUSTOM'] : ['ACME_HTTP', 'INTERNAL', 'CUSTOM']

  return (
    <div className="advanced" aria-label={t('PublishDialog.advanced.title')} role="group">
      <h3 className="advanced__title">{t('PublishDialog.advanced.title')}</h3>

      <Group legend={t('PublishDialog.advanced.aliases')} help={t('PublishDialog.advanced.aliasesHelp')}>
        {state.aliases.map((alias, i) => (
          <div key={i} className="advanced__row">
            <TextField
              hideLabel
              label={t('PublishDialog.advanced.alias')}
              value={alias}
              placeholder="www.example.com"
              autoCapitalize="off"
              spellCheck={false}
              onChange={(e) => onChange({ aliases: state.aliases.map((a, j) => (j === i ? e.target.value : a)) })}
            />
            <IconButton aria-label={t('PublishDialog.advanced.removeAlias', { name: alias || String(i + 1) })} icon={X} onClick={() => onChange({ aliases: state.aliases.filter((_, j) => j !== i) })} />
          </div>
        ))}
        <Problem text={problem('aliases')} />
        <div>
          <Button size="sm" variant="secondary" onClick={() => onChange({ aliases: [...state.aliases, ''] })}>
            <Plus size={16} strokeWidth={1.75} aria-hidden="true" />
            {t('PublishDialog.advanced.addAlias')}
          </Button>
        </div>
      </Group>

      <div className="advanced__grid">
        <TextField label={t('PublishDialog.advanced.pathPrefix')} help={t('PublishDialog.advanced.pathPrefixHelp')} error={problem('pathPrefix')} value={state.pathPrefix} placeholder="/blog" autoCapitalize="off" spellCheck={false} onChange={(e) => onChange({ pathPrefix: e.target.value })} />
        <Select label={t('PublishDialog.advanced.scheme')} help={t('PublishDialog.advanced.schemeHelp')} value={state.backendScheme} onChange={(e) => onChange({ backendScheme: e.target.value as BackendScheme })}>
          {SCHEMES.map((s) => (
            <option key={s} value={s}>
              {t(`PublishDialog.advanced.scheme_${s}`)}
            </option>
          ))}
        </Select>
        {proxyType === 'TRAEFIK' && (
          <TextField label={t('PublishDialog.advanced.entrypoint')} help={t('PublishDialog.advanced.entrypointHelp')} value={state.entrypoint} placeholder={EXAMPLE.entrypoint} autoCapitalize="off" spellCheck={false} onChange={(e) => onChange({ entrypoint: e.target.value })} />
        )}
      </div>

      <Group legend={t('PublishDialog.advanced.https')}>
        <Select label={t('PublishDialog.advanced.certMode')} value={state.certMode} onChange={(e) => onChange({ certMode: e.target.value as CertMode | '' })}>
          {allowDefaultMode && <option value="">{t('PublishDialog.advanced.certMode_default')}</option>}
          {modes.map((m) => (
            <option key={m} value={m}>
              {t(`PublishDialog.advanced.certMode_${m}`)}
            </option>
          ))}
        </Select>
        {state.certMode === 'CUSTOM' && (
          <>
            <Select
              label={t('PublishDialog.advanced.certificate')}
              help={certificates.data && certificates.data.length === 0 ? t('PublishDialog.advanced.noCertificates') : undefined}
              error={problem('certificateId')}
              value={state.certificateId}
              onChange={(e) => onChange({ certificateId: e.target.value })}
            >
              <option value="">{t('PublishDialog.advanced.certificatePlaceholder')}</option>
              {(certificates.data ?? []).map((c) => (
                <option key={c.id} value={c.id}>
                  {c.name} ({c.domains.join(', ')})
                </option>
              ))}
            </Select>
            <div>
              <Button size="sm" variant="ghost" onClick={onManageCertificates}>
                {t('PublishDialog.advanced.manageCertificates')}
              </Button>
            </div>
          </>
        )}
        <Switch label={t('PublishDialog.advanced.redirect')} help={t('PublishDialog.advanced.redirectHelp')} checked={state.redirectHttps} onChange={(e) => onChange({ redirectHttps: e.target.checked })} />
      </Group>

      <Group legend={t('PublishDialog.advanced.basicAuth')} help={t('PublishDialog.advanced.basicAuthHelp')}>
        {state.users.map((u) => (
          <div key={u.key} className="advanced__row advanced__row--pair">
            <TextField
              hideLabel
              label={t('PublishDialog.advanced.userName')}
              value={u.name}
              readOnly={u.existing}
              autoCapitalize="off"
              spellCheck={false}
              autoComplete="off"
              onChange={(e) => onChange({ users: state.users.map((x) => (x.key === u.key ? { ...x, name: e.target.value } : x)) })}
            />
            <TextField
              hideLabel
              label={t('PublishDialog.advanced.password')}
              type="password"
              value={u.password}
              placeholder={u.existing ? t('PublishDialog.advanced.passwordKeep') : ''}
              autoComplete="new-password"
              data-1p-ignore
              data-lpignore="true"
              onChange={(e) => onChange({ users: state.users.map((x) => (x.key === u.key ? { ...x, password: e.target.value } : x)) })}
            />
            <IconButton aria-label={t('PublishDialog.advanced.removeUser', { name: u.name || '…' })} icon={X} onClick={() => onChange({ users: state.users.filter((x) => x.key !== u.key) })} />
          </div>
        ))}
        <Problem text={problem('users')} />
        <div>
          <Button size="sm" variant="secondary" onClick={() => onChange({ users: [...state.users, { key: newKey(), name: '', password: '', existing: false }] })}>
            <Plus size={16} strokeWidth={1.75} aria-hidden="true" />
            {t('PublishDialog.advanced.addUser')}
          </Button>
        </div>
      </Group>

      <Group legend={t('PublishDialog.advanced.headers')} help={t('PublishDialog.advanced.headersHelp')}>
        {state.headers.map((h) => (
          <div key={h.key} className="advanced__row advanced__row--pair">
            <TextField
              hideLabel
              label={t('PublishDialog.advanced.headerName')}
              value={h.name}
              placeholder={EXAMPLE.headerName}
              autoCapitalize="off"
              spellCheck={false}
              onChange={(e) => onChange({ headers: state.headers.map((x) => (x.key === h.key ? { ...x, name: e.target.value } : x)) })}
            />
            <TextField
              hideLabel
              label={t('PublishDialog.advanced.headerValue')}
              value={h.value}
              placeholder={EXAMPLE.headerValue}
              spellCheck={false}
              onChange={(e) => onChange({ headers: state.headers.map((x) => (x.key === h.key ? { ...x, value: e.target.value } : x)) })}
            />
            <IconButton aria-label={t('PublishDialog.advanced.removeHeader', { name: h.name || '…' })} icon={X} onClick={() => onChange({ headers: state.headers.filter((x) => x.key !== h.key) })} />
          </div>
        ))}
        <Problem text={problem('headers')} />
        <div>
          <Button size="sm" variant="secondary" onClick={() => onChange({ headers: [...state.headers, { key: newKey(), name: '', value: '' }] })}>
            <Plus size={16} strokeWidth={1.75} aria-hidden="true" />
            {t('PublishDialog.advanced.addHeader')}
          </Button>
        </div>
      </Group>
    </div>
  )
}
