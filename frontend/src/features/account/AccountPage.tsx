import { useEffect, useId, useRef, useState, type FormEvent } from 'react'
import { Bell, Check, Circle } from 'lucide-react'
import { Badge, Button, Card, EmptyState, PageHeader, Select, Skeleton, Switch, TextField } from '../../shared/ui/index.ts'
import { ApiError } from '../../shared/api/client.ts'
import { useQueryClient } from '@tanstack/react-query'
import {
  CURRENT_USER_KEY,
  TwoFactorEnrollment,
  twoFactorErrorMessage,
  useChangeLocale,
  useChangePassword,
  useDisableTwoFactor,
  type CurrentUser,
} from '../auth/index.ts'
import { fieldError } from '../auth/fieldError.ts'
import { checkPassword, isPasswordValid, violationMessage } from '../auth/passwordPolicy.ts'
import {
  NOTIFICATION_TYPE_LABELS,
  useNotificationPreferences,
  useSavePreferences,
  type NotificationPreference,
  type NotificationType,
} from '../notifications/api.ts'
import { TokensSection } from './tokens/TokensSection.tsx'
import { useTranslation } from 'react-i18next'
import i18n from 'i18next'
import { SUPPORTED_LANGUAGES } from '../../shared/i18n/index.ts'
import './AccountPage.css'

function passwordErrors(error: unknown): string[] {
  if (error instanceof ApiError && error.status === 400) {
    if (error.code === 'INVALID_CURRENT_PASSWORD') return [i18n.t('account:AccountPage.theCurrentPasswordIsIncorrect')]
    if (error.code === 'PASSWORD_POLICY') {
      const violations = error.violations ?? []
      return violations.length > 0
        ? violations.map(violationMessage)
        : [i18n.t('account:AccountPage.thePasswordDoesNotMeet')]
    }
  }
  return [i18n.t('account:AccountPage.couldNotChangeThePassword')]
}

function PasswordSection({ username }: { username: string }) {
  const { t } = useTranslation('account')
  const changePassword = useChangePassword()
  const [current, setCurrent] = useState('')
  const [next, setNext] = useState('')
  const [confirmation, setConfirmation] = useState('')
  const errorId = useId()
  const errorCode = changePassword.error instanceof ApiError ? changePassword.error.code : undefined
  // Which field the server error belongs to: the wrong current password, or the new one breaking the policy.
  const invalidField = errorCode === 'INVALID_CURRENT_PASSWORD' ? 0 : errorCode === 'PASSWORD_POLICY' ? 1 : -1

  const rules = checkPassword(next, username, confirmation)
  const canSubmit =
    current.length > 0 && isPasswordValid(next, username, confirmation) && !changePassword.isPending

  const onSubmit = (event: FormEvent) => {
    event.preventDefault()
    if (!canSubmit) return
    changePassword.mutate(
      { currentPassword: current, newPassword: next },
      {
        onSuccess: () => {
          setCurrent('')
          setNext('')
          setConfirmation('')
        },
      },
    )
  }

  return (
    <Card className="account__card">
    <form className="account__form" onSubmit={onSubmit}>
      <h2 className="account__heading">{t('AccountPage.changePassword')}</h2>
      {(
        [
          [t('AccountPage.currentPassword'), current, setCurrent, 'current-password'],
          [t('AccountPage.newPassword'), next, setNext, 'new-password'],
          [t('AccountPage.confirmNewPassword'), confirmation, setConfirmation, 'new-password'],
        ] as const
      ).map(([label, value, set, autoComplete], index) => (
        <label key={label} className="account__field">
          <span>{label}</span>
          <input
            type="password"
            value={value}
            onChange={(e) => set(e.target.value)}
            autoComplete={autoComplete}
            disabled={changePassword.isPending}
            {...fieldError(errorId, invalidField === index)}
          />
        </label>
      ))}
      <ul className="account__rules">
        {rules.map((rule) => (
          <li key={rule.id} className="account__rule" data-met={rule.met}>
            <span aria-hidden="true" className="account__mark">
              {rule.met ? <Check size={16} aria-hidden="true" /> : <Circle size={16} aria-hidden="true" />}
            </span>
            <span className="visually-hidden">{rule.met ? t('common:Checklist.met') : t('common:Checklist.notMet')}</span>
            {rule.label}
          </li>
        ))}
      </ul>
      <div id={errorId} className="account__error" role="alert">
        {changePassword.isError &&
          passwordErrors(changePassword.error).map((message) => <p key={message}>{message}</p>)}
      </div>
      <div className="account__success" role="status">
        {changePassword.isSuccess && t('AccountPage.passwordChanged')}
      </div>
      <div className="account__actions">
        <Button variant="primary" type="submit" disabled={!canSubmit} loading={changePassword.isPending}>
          {changePassword.isPending ? t('AccountPage.saving') : t('AccountPage.changePassword')}
        </Button>
      </div>
    </form>
    </Card>
  )
}

/** A language switch remounts the app (see `LanguageBoundary`); the new select takes the focus back. */
let refocusLanguageSelect = false

function LanguageSection({ locale }: { locale: string | null | undefined }) {
  const { t } = useTranslation('account')
  const change = useChangeLocale()
  const select = useRef<HTMLSelectElement>(null)
  useEffect(() => {
    if (refocusLanguageSelect) select.current?.focus()
    refocusLanguageSelect = false
  }, [])
  return (
    <Card className="account__card">
      <Select
        ref={select}
        label={t('AccountPage.language')}
        value={locale ?? ''}
        onChange={(e) => {
          refocusLanguageSelect = true
          change.mutate(e.target.value || null, { onError: () => (refocusLanguageSelect = false) })
        }}
        disabled={change.isPending}
        help={t('AccountPage.automaticFollowsYourBrowserLanguage')}
        error={change.isError ? t('AccountPage.couldNotSaveTheLanguage') : null}
      >
        <option value="">{t('AccountPage.automatic')}</option>
        {SUPPORTED_LANGUAGES.map((language) => (
          <option key={language} value={language}>
            {t(`AccountPage.language_${language}`)}
          </option>
        ))}
      </Select>
    </Card>
  )
}

function PreferencesSection() {
  const { t } = useTranslation('account')
  const preferences = useNotificationPreferences()
  const save = useSavePreferences()

  const toggle = (type: NotificationType) => {
    if (!preferences.data) return
    save.mutate(
      preferences.data.map((p: NotificationPreference) =>
        p.type === type ? { ...p, inAppEnabled: !p.inAppEnabled } : p,
      ),
    )
  }

  return (
    <Card className="account__card" role="region" aria-labelledby="account-notifications">
      <h2 className="account__heading" id="account-notifications">
        {t('AccountPage.notifications')}
      </h2>
      {preferences.isPending && (
        <div aria-busy="true" className="account__loading">
          <Skeleton height="1.5rem" />
          <Skeleton height="1.5rem" />
        </div>
      )}
      {preferences.isError && <p role="alert" className="account__error">{t('AccountPage.couldNotLoadNotificationPreferences')}</p>}
      {preferences.data?.length === 0 && (
        <EmptyState
          icon={Bell}
          title={t('AccountPage.noNotificationSettings')}
          description={t('AccountPage.noNotificationSettingsHint')}
        />
      )}
      {preferences.data?.map((p) => (
        <Switch
          key={p.type}
          label={NOTIFICATION_TYPE_LABELS[p.type]}
          checked={p.inAppEnabled}
          onChange={() => toggle(p.type)}
        />
      ))}
      <div className="account__error" role="alert">
        {save.isError && t('AccountPage.couldNotSaveYourPreference')}
      </div>
    </Card>
  )
}

function DisableForm({ onCancel }: { onCancel: () => void }) {
  const { t } = useTranslation('account')
  const disable = useDisableTwoFactor()
  const [password, setPassword] = useState('')
  const [code, setCode] = useState('')
  const onSubmit = (event: FormEvent) => {
    event.preventDefault()
    if (disable.isPending || !password || !code.trim()) return
    disable.mutate({ password, code: code.trim() }, { onSuccess: () => setPassword('') })
  }
  return (
    <form className="account__form" onSubmit={onSubmit}>
      <TextField label={t('AccountPage.password')} type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="current-password" />
      <TextField label={t('AccountPage.authenticationCode')} value={code} onChange={(e) => setCode(e.target.value)} autoComplete="one-time-code" help={t('AccountPage.aCodeFromYourApp')} />
      <div className="account__error" role="alert">
        {disable.isError && twoFactorErrorMessage(disable.error, t('AccountPage.couldNotDisableTwoFactor'))}
      </div>
      <div className="account__row">
        <Button variant="ghost" onClick={onCancel}>
          {t('AccountPage.cancel')}
        </Button>
        <Button variant="danger" type="submit" loading={disable.isPending}>
          {t('AccountPage.disable')}
        </Button>
      </div>
    </form>
  )
}

function TwoFactorSection({ enabled }: { enabled: boolean }) {
  const { t } = useTranslation('account')
  const queryClient = useQueryClient()
  const [showCodes, setShowCodes] = useState(false)
  const [disabling, setDisabling] = useState(false)

  return (
    <Card className="account__card" role="region" aria-labelledby="account-2fa">
      <h2 className="account__heading" id="account-2fa">
        {t('AccountPage.twoFactorAuthentication')}
      </h2>
      {/* After confirming, `enabled` flips but the enrolment stays mounted until the recovery codes are dismissed. */}
      {!enabled || showCodes ? (
        <>
          {!showCodes && (
            <p className="account__row">
              <Badge tone="muted">{t('AccountPage.off')}</Badge>
              <span>{t('AccountPage.protectYourAccountWithA')}</span>
            </p>
          )}
          <TwoFactorEnrollment
            onConfirmed={() => {
              setShowCodes(true)
              void queryClient.invalidateQueries({ queryKey: CURRENT_USER_KEY })
            }}
            onFinished={() => setShowCodes(false)}
          />
        </>
      ) : (
        <>
          <p className="account__row">
            <Badge tone="ok">{t('AccountPage.enabled')}</Badge>
            <span>{t('AccountPage.aCodeFromYourAuthenticator')}</span>
          </p>
          {disabling ? (
            <DisableForm onCancel={() => setDisabling(false)} />
          ) : (
            <div className="account__actions">
              <Button onClick={() => setDisabling(true)}>{t('AccountPage.disableTwoFactorAuthentication')}</Button>
            </div>
          )}
        </>
      )}
    </Card>
  )
}

/** Personal settings: account info, password change, notification preferences. */
export function AccountPage({
  user,
}: {
  user: Pick<CurrentUser, 'username' | 'role' | 'twoFactorEnabled' | 'externalIssuer' | 'locale'>
}) {
  const { t } = useTranslation('account')
  return (
    <div className="ui-page ui-page--narrow account">
      <PageHeader title={t('AccountPage.account')} subtitle={t('AccountPage.yourSignInPasswordApi')} />
      <Card className="account__card" role="region" aria-labelledby="account-info">
        <h2 className="account__heading" id="account-info">
          {t('AccountPage.account')}
        </h2>
        <dl className="account__info">
          <dt>{t('AccountPage.username')}</dt>
          <dd>{user.username}</dd>
          <dt>{t('AccountPage.role')}</dt>
          <dd>{user.role}</dd>
          {user.externalIssuer && (
            <>
              <dt>{t('AccountPage.singleSignOn')}</dt>
              <dd>{t('AccountPage.linkedToExternalissuer', { externalIssuer: user.externalIssuer })}</dd>
            </>
          )}
        </dl>
      </Card>
      <LanguageSection locale={user.locale} />
      {user.externalIssuer ? (
        <Card className="account__card" role="region" aria-labelledby="account-sso-managed">
          <h2 className="account__heading" id="account-sso-managed">
            {t('AccountPage.signInManagedBySso')}
          </h2>
          <p>{t('AccountPage.signInManagedBySsoText')}</p>
        </Card>
      ) : (
        <>
          <PasswordSection username={user.username} />
          <TwoFactorSection enabled={user.twoFactorEnabled === true} />
        </>
      )}
      <TokensSection />
      <PreferencesSection />
    </div>
  )
}
