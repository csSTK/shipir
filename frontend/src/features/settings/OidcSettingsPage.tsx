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
import { useTranslation } from 'react-i18next'
import { Trans } from '../../shared/i18n/Trans.tsx'
import i18n from 'i18next'
import './SettingsPage.css'

const ROLES: OidcRole[] = ['ADMIN', 'OPERATOR', 'VIEWER']

const ERROR_MESSAGES: Record<string, string> = {
  get INVALID_ISSUER_URL() { return i18n.t('settings:OidcSettingsPage.theIssuerUrlMustBe') },
  get CLIENT_ID_REQUIRED() { return i18n.t('settings:OidcSettingsPage.enterTheClientId') },
  get INVALID_SCOPES() { return i18n.t('settings:OidcSettingsPage.theScopesAreNotValid') },
  get INVALID_ROLE_MAPPING() { return i18n.t('settings:OidcSettingsPage.theRoleMappingIsNot') },
  get LOCAL_LOGIN_REQUIRED() { return i18n.t('settings:OidcSettingsPage.localLoginCanOnlyBe') },
  get OIDC_NOT_CONFIGURED() { return i18n.t('settings:OidcSettingsPage.saveTheIssuerClientId') },
  get SECRET_REQUIRED_FOR_NEW_ENDPOINT() { return i18n.t('settings:OidcSettingsPage.secretRequiredForNewEndpoint') },
  get ENCRYPTION_KEY_MISSING() { return i18n.t('settings:OidcSettingsPage.theServerHasNoEncryption') },
}

function errorMessage(error: unknown, fallback: string): string {
  if (error instanceof ApiError && error.code && ERROR_MESSAGES[error.code]) return ERROR_MESSAGES[error.code]
  if (error instanceof ApiError && error.status === 400) return i18n.t('settings:OidcSettingsPage.checkTheEnteredValues')
  return fallback
}

const TEST_MESSAGES: Record<OidcTestStatus, { ok: boolean; text: string }> = {
  OK: { ok: true, get text() { return i18n.t('settings:OidcSettingsPage.connectionSuccessful') } },
  UNREACHABLE: { ok: false, get text() { return i18n.t('settings:OidcSettingsPage.theProviderCouldNotBe') } },
  INVALID: { ok: false, get text() { return i18n.t('settings:OidcSettingsPage.theProviderAnsweredButIts') } },
}

const parseGroups = (text: string) =>
  text
    .split(',')
    .map((g) => g.trim())
    .filter((g) => g !== '')

function OidcForm({ initial }: { initial: OidcSettings }) {
  const { t } = useTranslation('settings')
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
        onError: (err) => setProblem(errorMessage(err, t('OidcSettingsPage.couldNotSaveTheSingle'))),
      },
    )
  }

  function runTest() {
    setTestProblem(null)
    test.mutate(undefined, {
      onError: (err) => setTestProblem(errorMessage(err, t('OidcSettingsPage.couldNotRunTheTest'))),
    })
  }

  const secretHelp = clearSecret
    ? t('OidcSettingsPage.theStoredSecretIsRemoved')
    : initial.clientSecretConfigured
      ? t('OidcSettingsPage.currentSecretClientsecrethint', { clientSecretHint: initial.clientSecretHint ?? 'stored' })
      : t('OidcSettingsPage.noSecretStored')

  return (
    <Card>
      <form className="settings__form" onSubmit={submit} noValidate>
        <p className="settings__muted">
          {t('OidcSettingsPage.status')} <Badge tone={initial.enabled ? 'ok' : 'muted'}>{initial.enabled ? t('OidcSettingsPage.enabled') : t('OidcSettingsPage.disabled')}</Badge>
        </p>
        <p className="settings__muted">
          <Trans t={t} i18nKey="OidcSettingsPage.registerThisRedirectUriWith" values={{ redirectUri: initial.redirectUri }} components={{ code: <code className="settings__mono" /> }} />
        </p>
        <TextField
          label={t('OidcSettingsPage.issuerUrl')}
          type="url"
          value={issuerUrl}
          onChange={(e) => { setIssuerUrl(e.target.value); touch() }}
          autoComplete="off"
          placeholder="https://id.example.com/realms/main"
          help={t('OidcSettingsPage.leaveEmptyToSwitchSingle')}
        />
        <TextField label={t('OidcSettingsPage.clientId')} value={clientId} onChange={(e) => { setClientId(e.target.value); touch() }} autoComplete="off" />
        <TextField
          label={t('OidcSettingsPage.clientSecret')}
          type="password"
          value={secret}
          disabled={clearSecret}
          onChange={(e) => { setSecret(e.target.value); touch() }}
          autoComplete="off"
          placeholder={initial.clientSecretConfigured ? t('OidcSettingsPage.leaveEmptyToKeepThe') : ''}
          help={secretHelp}
        />
        {(initial.clientSecretConfigured || clearSecret) && (
          <div>
            <Button size="sm" variant="ghost" onClick={() => { setClearSecret(!clearSecret); setSecret(''); touch() }}>
              {clearSecret ? t('OidcSettingsPage.keepStoredSecret') : t('OidcSettingsPage.removeStoredSecret')}
            </Button>
          </div>
        )}
        <TextField label={t('OidcSettingsPage.scopes')} value={scopes} onChange={(e) => { setScopes(e.target.value); touch() }} autoComplete="off" help={t('OidcSettingsPage.separatedBySpaces')} />
        <TextField
          label={t('OidcSettingsPage.groupsClaim')}
          value={groupsClaim}
          onChange={(e) => { setGroupsClaim(e.target.value); touch() }}
          autoComplete="off"
          help={t('OidcSettingsPage.nameOfTheIdToken')}
        />
        <fieldset className="settings__group">
          <legend className="settings__legend">{t('OidcSettingsPage.roleMapping')}</legend>
          <p className="settings__muted">{t('OidcSettingsPage.groupNamesPerShipirRole')}</p>
          {ROLES.map((role) => (
            <TextField
              key={role}
              label={t('OidcSettingsPage.charatLowercaseGroups', { charAt: role.charAt(0), lowerCase: role.slice(1).toLowerCase() })}
              value={groups[role]}
              onChange={(e) => { setGroups((g) => ({ ...g, [role]: e.target.value })); touch() }}
              autoComplete="off"
            />
          ))}
        </fieldset>
        <Switch
          label={t('OidcSettingsPage.createAccountsAutomaticallyOnFirst')}
          checked={autoProvision}
          onChange={(e) => { setAutoProvision(e.target.checked); touch() }}
        />
        <Switch
          label={t('OidcSettingsPage.allowLocalUsernameAndPassword')}
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
            {t('OidcSettingsPage.singleSignOnSettingsSaved')}
          </p>
        )}
        <div className="settings__actions">
          <Button onClick={runTest} loading={test.isPending}>
            {t('OidcSettingsPage.testConnection')}
          </Button>
          <Button variant="primary" type="submit" loading={save.isPending}>
            {t('OidcSettingsPage.save')}
          </Button>
        </div>
        <p className="settings__muted">{t('OidcSettingsPage.theTestUsesTheSaved')}</p>
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
  const { t } = useTranslation('settings')
  const { data, isPending, isError } = useOidcSettings()
  return (
    <SettingsPage
      title={t('OidcSettingsPage.singleSignOn')}
      subtitle={t('OidcSettingsPage.letUsersSignInWith')}
    >
      {isPending && <Skeleton width="100%" height="var(--space-8)" />}
      {isError && (
        <p role="alert" className="settings__error">
          {t('OidcSettingsPage.couldNotLoadTheSingle')}
        </p>
      )}
      {data && <OidcForm initial={data} />}
    </SettingsPage>
  )
}
