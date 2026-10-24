import { useState, type FormEvent } from 'react'
import { Badge, Button, Card, Skeleton, Switch, TextField } from '../../shared/ui/index.ts'
import { ApiError } from '../../shared/api/client.ts'
import {
  useOidcSettings,
  useSaveOidcSettings,
  useTestOidcConnection,
  type OidcRole,
  type OidcSettings,
  type OidcTestStatus,
} from './api.ts'
import { SettingsPage } from './SettingsPage.tsx'
import './SettingsPage.css'

const ROLES: OidcRole[] = ['ADMIN', 'OPERATOR', 'VIEWER']

const ERROR_MESSAGES: Record<string, string> = {
  INVALID_ISSUER_URL: 'The issuer URL must be a valid https URL.',
  CLIENT_ID_REQUIRED: 'Enter the client ID.',
  INVALID_SCOPES: 'The scopes are not valid. Separate them with spaces.',
  INVALID_ROLE_MAPPING: 'The role mapping is not valid.',
  LOCAL_LOGIN_REQUIRED: 'Local login can only be switched off while single sign-on is fully configured (issuer, client ID and secret).',
  OIDC_NOT_CONFIGURED: 'Save the issuer, client ID and client secret first.',
  ENCRYPTION_KEY_MISSING: 'The server has no encryption key. Set SHIPIR_ENCRYPTION_KEY to store the client secret.',
}

function errorMessage(error: unknown, fallback: string): string {
  if (error instanceof ApiError && error.code && ERROR_MESSAGES[error.code]) return ERROR_MESSAGES[error.code]
  if (error instanceof ApiError && error.status === 400) return 'Check the entered values.'
  return fallback
}

const TEST_MESSAGES: Record<OidcTestStatus, { ok: boolean; text: string }> = {
  OK: { ok: true, text: 'Connection successful.' },
  UNREACHABLE: { ok: false, text: 'The provider could not be reached.' },
  INVALID: { ok: false, text: 'The provider answered, but its discovery document does not match the issuer.' },
}

const parseGroups = (text: string) =>
  text
    .split(',')
    .map((g) => g.trim())
    .filter((g) => g !== '')

function OidcForm({ initial }: { initial: OidcSettings }) {
  const [issuerUrl, setIssuerUrl] = useState(initial.issuerUrl ?? '')
  const [clientId, setClientId] = useState(initial.clientId ?? '')
  const [secret, setSecret] = useState('')
  const [clearSecret, setClearSecret] = useState(false)
  const [scopes, setScopes] = useState(initial.scopes)
  const [groupsClaim, setGroupsClaim] = useState(initial.groupsClaim)
  const [groups, setGroups] = useState<Record<OidcRole, string>>(() => ({
    ADMIN: (initial.roleMapping.ADMIN ?? []).join(', '),
    OPERATOR: (initial.roleMapping.OPERATOR ?? []).join(', '),
    VIEWER: (initial.roleMapping.VIEWER ?? []).join(', '),
  }))
  const [autoProvision, setAutoProvision] = useState(initial.autoProvision)
  const [localLogin, setLocalLogin] = useState(initial.localLoginEnabled)
  const [problem, setProblem] = useState<string | null>(null)
  const [saved, setSaved] = useState(false)
  const [testProblem, setTestProblem] = useState<string | null>(null)
  const save = useSaveOidcSettings()
  const test = useTestOidcConnection()
  const testResult = test.isPending || !test.data ? null : TEST_MESSAGES[test.data.status]

  const touch = () => setSaved(false)

  function submit(e: FormEvent) {
    e.preventDefault()
    setSaved(false)
    const issuer = issuerUrl.trim()
    if (issuer !== '' && clientId.trim() === '') return setProblem(ERROR_MESSAGES.CLIENT_ID_REQUIRED)
    setProblem(null)
    const key = secret.trim()
    save.mutate(
      {
        issuerUrl: issuer,
        clientId: clientId.trim(),
        ...(clearSecret ? { clientSecret: '' } : key !== '' ? { clientSecret: key } : {}),
        scopes: scopes.trim(),
        groupsClaim: groupsClaim.trim(),
        roleMapping: {
          ADMIN: parseGroups(groups.ADMIN),
          OPERATOR: parseGroups(groups.OPERATOR),
          VIEWER: parseGroups(groups.VIEWER),
        },
        autoProvision,
        localLoginEnabled: localLogin,
      },
      {
        onSuccess: () => {
          setSaved(true)
          setSecret('')
          setClearSecret(false)
          test.reset()
        },
        onError: (err) => setProblem(errorMessage(err, 'Could not save the single sign-on settings. Try again.')),
      },
    )
  }

  function runTest() {
    setTestProblem(null)
    test.mutate(undefined, {
      onError: (err) => setTestProblem(errorMessage(err, 'Could not run the test. Try again.')),
    })
  }

  const secretHelp = clearSecret
    ? 'The stored secret is removed when you save.'
    : initial.clientSecretConfigured
      ? `Current secret: ${initial.clientSecretHint ?? 'stored'}`
      : 'No secret stored.'

  return (
    <Card>
      <form className="settings__form" onSubmit={submit} noValidate>
        <p className="settings__muted">
          Status: <Badge tone={initial.enabled ? 'ok' : 'muted'}>{initial.enabled ? 'Enabled' : 'Disabled'}</Badge>
        </p>
        <p className="settings__muted">
          Register this redirect URI with your provider: <code className="settings__mono">{initial.redirectUri}</code>
        </p>
        <TextField
          label="Issuer URL"
          type="url"
          value={issuerUrl}
          onChange={(e) => { setIssuerUrl(e.target.value); touch() }}
          autoComplete="off"
          placeholder="https://id.example.com/realms/main"
          help="Leave empty to switch single sign-on off."
        />
        <TextField label="Client ID" value={clientId} onChange={(e) => { setClientId(e.target.value); touch() }} autoComplete="off" />
        <TextField
          label="Client secret"
          type="password"
          value={secret}
          disabled={clearSecret}
          onChange={(e) => { setSecret(e.target.value); touch() }}
          autoComplete="off"
          placeholder={initial.clientSecretConfigured ? 'Leave empty to keep the current secret' : ''}
          help={secretHelp}
        />
        {(initial.clientSecretConfigured || clearSecret) && (
          <div>
            <Button size="sm" variant="ghost" onClick={() => { setClearSecret(!clearSecret); setSecret(''); touch() }}>
              {clearSecret ? 'Keep stored secret' : 'Remove stored secret'}
            </Button>
          </div>
        )}
        <TextField label="Scopes" value={scopes} onChange={(e) => { setScopes(e.target.value); touch() }} autoComplete="off" help="Separated by spaces." />
        <TextField
          label="Groups claim"
          value={groupsClaim}
          onChange={(e) => { setGroupsClaim(e.target.value); touch() }}
          autoComplete="off"
          help="Name of the ID token claim that lists the user's groups."
        />
        <fieldset className="settings__group">
          <legend className="settings__legend">Role mapping</legend>
          <p className="settings__muted">Group names per Shipir role, separated by commas. The highest matching role wins; no match denies sign-in.</p>
          {ROLES.map((role) => (
            <TextField
              key={role}
              label={`${role.charAt(0)}${role.slice(1).toLowerCase()} groups`}
              value={groups[role]}
              onChange={(e) => { setGroups((g) => ({ ...g, [role]: e.target.value })); touch() }}
              autoComplete="off"
            />
          ))}
        </fieldset>
        <Switch
          label="Create accounts automatically on first sign-in"
          checked={autoProvision}
          onChange={(e) => { setAutoProvision(e.target.checked); touch() }}
        />
        <Switch
          label="Allow local username and password login"
          checked={localLogin}
          onChange={(e) => { setLocalLogin(e.target.checked); touch() }}
        />
        {problem && (
          <p role="alert" className="settings__error">
            {problem}
          </p>
        )}
        {saved && (
          <p role="status" className="settings__ok">
            Single sign-on settings saved.
          </p>
        )}
        <div className="settings__actions">
          <Button onClick={runTest} loading={test.isPending}>
            Test connection
          </Button>
          <Button variant="primary" type="submit" loading={save.isPending}>
            Save
          </Button>
        </div>
        <p className="settings__muted">The test uses the saved issuer. Save changes first.</p>
        {testResult && (
          <p role="status" className={testResult.ok ? 'settings__ok' : 'settings__error'}>
            {testResult.text}
          </p>
        )}
        {testProblem && (
          <p role="alert" className="settings__error">
            {testProblem}
          </p>
        )}
      </form>
    </Card>
  )
}

/** Settings > Single sign-on: ADMIN configures the OpenID Connect provider. The caller restricts access to ADMIN. */
export function OidcSettingsPage() {
  const { data, isPending, isError } = useOidcSettings()
  return (
    <SettingsPage
      title="Single sign-on"
      subtitle="Let users sign in with an OpenID Connect provider. The client secret is stored encrypted and never shown again."
    >
      {isPending && <Skeleton width="100%" height="var(--space-8)" />}
      {isError && (
        <p role="alert" className="settings__error">
          Could not load the single sign-on settings.
        </p>
      )}
      {data && <OidcForm initial={data} />}
    </SettingsPage>
  )
}
