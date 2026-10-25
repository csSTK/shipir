import { useState, type FormEvent } from 'react'
import { Badge, Button, Card, PageHeader, Skeleton, Switch, TextField } from '../../shared/ui/index.ts'
import { ApiError } from '../../shared/api/client.ts'
import { useQueryClient } from '@tanstack/react-query'
import {
  CURRENT_USER_KEY,
  TwoFactorEnrollment,
  twoFactorErrorMessage,
  useChangePassword,
  useDisableTwoFactor,
  type CurrentUser,
} from '../auth/index.ts'
import { checkPassword, isPasswordValid, violationMessage } from '../auth/passwordPolicy.ts'
import {
  NOTIFICATION_TYPE_LABELS,
  useNotificationPreferences,
  useSavePreferences,
  type NotificationPreference,
  type NotificationType,
} from '../notifications/api.ts'
import { TokensSection } from './tokens/TokensSection.tsx'
import './AccountPage.css'

function passwordErrors(error: unknown): string[] {
  if (error instanceof ApiError && error.status === 400) {
    if (error.code === 'INVALID_CURRENT_PASSWORD') return ['The current password is incorrect.']
    if (error.code === 'PASSWORD_POLICY') {
      const violations = error.violations ?? []
      return violations.length > 0
        ? violations.map(violationMessage)
        : ['The password does not meet the requirements.']
    }
  }
  return ['Could not change the password. Please try again.']
}

function PasswordSection({ username }: { username: string }) {
  const changePassword = useChangePassword()
  const [current, setCurrent] = useState('')
  const [next, setNext] = useState('')
  const [confirmation, setConfirmation] = useState('')

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
      <h2 className="account__heading">Change password</h2>
      {(
        [
          ['Current password', current, setCurrent, 'current-password'],
          ['New password', next, setNext, 'new-password'],
          ['Confirm new password', confirmation, setConfirmation, 'new-password'],
        ] as const
      ).map(([label, value, set, autoComplete]) => (
        <label key={label} className="account__field">
          <span>{label}</span>
          <input
            type="password"
            value={value}
            onChange={(e) => set(e.target.value)}
            autoComplete={autoComplete}
            disabled={changePassword.isPending}
          />
        </label>
      ))}
      <ul className="account__rules">
        {rules.map((rule) => (
          <li key={rule.id} data-met={rule.met}>
            <span aria-hidden="true">{rule.met ? '✓' : '○'} </span>
            {rule.label}
          </li>
        ))}
      </ul>
      <div className="account__error" role="alert">
        {changePassword.isError &&
          passwordErrors(changePassword.error).map((message) => <p key={message}>{message}</p>)}
      </div>
      <div className="account__success" role="status">
        {changePassword.isSuccess && 'Password changed.'}
      </div>
      <Button className="account__button" variant="primary" type="submit" disabled={!canSubmit} loading={changePassword.isPending}>
        {changePassword.isPending ? 'Saving…' : 'Change password'}
      </Button>
    </form>
    </Card>
  )
}

function PreferencesSection() {
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
        Notifications
      </h2>
      {preferences.isPending && (
        <div aria-busy="true" className="account__loading">
          <Skeleton height="1.5rem" />
          <Skeleton height="1.5rem" />
        </div>
      )}
      {preferences.isError && <p role="alert" className="account__error">Could not load notification preferences.</p>}
      {preferences.data?.map((p) => (
        <Switch
          key={p.type}
          label={NOTIFICATION_TYPE_LABELS[p.type]}
          checked={p.inAppEnabled}
          onChange={() => toggle(p.type)}
        />
      ))}
      <div className="account__error" role="alert">
        {save.isError && 'Could not save your preference. Please try again.'}
      </div>
    </Card>
  )
}

function DisableForm({ onCancel }: { onCancel: () => void }) {
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
      <TextField label="Password" type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="current-password" />
      <TextField label="Authentication code" value={code} onChange={(e) => setCode(e.target.value)} autoComplete="one-time-code" help="A code from your app or a recovery code." />
      <div className="account__error" role="alert">
        {disable.isError && twoFactorErrorMessage(disable.error, 'Could not disable two-factor authentication. Try again.')}
      </div>
      <div className="account__row">
        <Button variant="ghost" onClick={onCancel}>
          Cancel
        </Button>
        <Button variant="danger" type="submit" loading={disable.isPending}>
          Disable
        </Button>
      </div>
    </form>
  )
}

function TwoFactorSection({ enabled }: { enabled: boolean }) {
  const queryClient = useQueryClient()
  const [showCodes, setShowCodes] = useState(false)
  const [disabling, setDisabling] = useState(false)

  return (
    <Card className="account__card" role="region" aria-labelledby="account-2fa">
      <h2 className="account__heading" id="account-2fa">
        Two-factor authentication
      </h2>
      {/* After confirming, `enabled` flips but the enrolment stays mounted until the recovery codes are dismissed. */}
      {!enabled || showCodes ? (
        <>
          {!showCodes && (
            <p className="account__row">
              <Badge tone="muted">Off</Badge>
              <span>Protect your account with a code from an authenticator app.</span>
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
            <Badge tone="ok">Enabled</Badge>
            <span>A code from your authenticator app is required at sign-in.</span>
          </p>
          {disabling ? (
            <DisableForm onCancel={() => setDisabling(false)} />
          ) : (
            <div>
              <Button onClick={() => setDisabling(true)}>Disable two-factor authentication</Button>
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
  user: Pick<CurrentUser, 'username' | 'role' | 'twoFactorEnabled' | 'externalIssuer'>
}) {
  return (
    <div className="account">
      <PageHeader title="Account" subtitle="Your sign-in, password, API tokens and notification preferences." />
      <Card className="account__card" role="region" aria-labelledby="account-info">
        <h2 className="account__heading" id="account-info">
          Account
        </h2>
        <dl className="account__info">
          <dt>Username</dt>
          <dd>{user.username}</dd>
          <dt>Role</dt>
          <dd>{user.role}</dd>
          {user.externalIssuer && (
            <>
              <dt>Single sign-on</dt>
              <dd>Linked to {user.externalIssuer}</dd>
            </>
          )}
        </dl>
      </Card>
      <PasswordSection username={user.username} />
      <TwoFactorSection enabled={user.twoFactorEnabled === true} />
      <TokensSection />
      <PreferencesSection />
    </div>
  )
}
