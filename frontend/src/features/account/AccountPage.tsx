import { useState, type FormEvent } from 'react'
import { ApiError } from '../../shared/api/client.ts'
import { useChangePassword, type CurrentUser } from '../auth/index.ts'
import { checkPassword, isPasswordValid, violationMessage } from '../auth/passwordPolicy.ts'
import {
  useNotificationPreferences,
  useSavePreferences,
  type NotificationPreference,
  type NotificationType,
} from '../notifications/api.ts'
import './AccountPage.css'

const LABELS: Record<NotificationType, string> = {
  HOST_DOWN: 'Host down',
  HOST_UP: 'Host up',
}

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
    <form className="account__card" onSubmit={onSubmit}>
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
      <button className="account__button" type="submit" disabled={!canSubmit}>
        {changePassword.isPending ? 'Saving…' : 'Change password'}
      </button>
    </form>
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
    <section className="account__card" aria-labelledby="account-notifications">
      <h2 className="account__heading" id="account-notifications">
        Notifications
      </h2>
      {preferences.isPending && <p aria-busy="true">Loading…</p>}
      {preferences.isError && <p role="alert">Could not load notification preferences.</p>}
      {preferences.data?.map((p) => (
        <label key={p.type} className="account__toggle">
          <span>{LABELS[p.type]}</span>
          <input
            type="checkbox"
            role="switch"
            checked={p.inAppEnabled}
            onChange={() => toggle(p.type)}
            aria-label={`${LABELS[p.type]} in-app notifications`}
          />
        </label>
      ))}
      <div className="account__error" role="alert">
        {save.isError && 'Could not save your preference. Please try again.'}
      </div>
    </section>
  )
}

/** Personal settings: account info, password change, notification preferences. */
export function AccountPage({ user }: { user: Pick<CurrentUser, 'username' | 'role'> }) {
  return (
    <div className="account">
      <h1 className="account__title">Account</h1>
      <section className="account__card" aria-labelledby="account-info">
        <h2 className="account__heading" id="account-info">
          Account
        </h2>
        <dl className="account__info">
          <dt>Username</dt>
          <dd>{user.username}</dd>
          <dt>Role</dt>
          <dd>{user.role}</dd>
        </dl>
      </section>
      <PasswordSection username={user.username} />
      <PreferencesSection />
    </div>
  )
}
