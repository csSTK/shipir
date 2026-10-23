import { useEffect, useRef, useState, type FormEvent } from 'react'
import { ApiError } from '../../shared/api/client.ts'
import { useConfirmPasswordReset, useRequestPasswordReset } from './api.ts'
import { checkPassword, isPasswordValid, violationMessage } from './passwordPolicy.ts'
import './LoginPage.css'
import './PasswordResetPage.css'

const NEUTRAL_MESSAGE = 'If the account exists, a code has been issued.'
const CODE_PATTERN = /^\d{6}$/

interface Props {
  onBack: () => void
  onDone: () => void
}

function confirmErrors(error: unknown): { invalidCode: boolean; messages: string[] } {
  if (error instanceof ApiError && error.status === 400) {
    if (error.code === 'INVALID_RESET_CODE') {
      return { invalidCode: true, messages: ['The code is invalid or expired.'] }
    }
    if (error.code === 'PASSWORD_POLICY') {
      const violations = error.violations ?? []
      return {
        invalidCode: false,
        messages: violations.length > 0 ? violations.map(violationMessage) : ['The password does not meet the requirements.'],
      }
    }
  }
  return { invalidCode: false, messages: ['Could not reset the password. Please try again.'] }
}

/** Self-service reset: request a one-time code for a username, then set a new password with it. */
export function PasswordResetPage({ onBack, onDone }: Props) {
  const [username, setUsername] = useState('')
  const [step, setStep] = useState<'request' | 'confirm'>('request')

  return step === 'request' ? (
    <RequestStep username={username} setUsername={setUsername} onBack={onBack} onRequested={() => setStep('confirm')} />
  ) : (
    <ConfirmStep username={username} onBack={onBack} onDone={onDone} onNewCode={() => setStep('request')} />
  )
}

function RequestStep({
  username,
  setUsername,
  onBack,
  onRequested,
}: {
  username: string
  setUsername: (value: string) => void
  onBack: () => void
  onRequested: () => void
}) {
  const request = useRequestPasswordReset()
  const ref = useRef<HTMLInputElement>(null)
  useEffect(() => ref.current?.focus(), [])

  const onSubmit = (event: FormEvent) => {
    event.preventDefault()
    if (request.isPending || username.trim() === '') return
    request.mutate({ username: username.trim() }, { onSuccess: onRequested })
  }

  return (
    <main className="login">
      <form className="login__card" onSubmit={onSubmit}>
        <h1 className="login__title">Reset your password</h1>
        <p className="reset__intro">Enter your username. If the account exists, a one-time code is issued.</p>
        <label className="login__field">
          <span>Username</span>
          <input
            ref={ref}
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            autoComplete="username"
            autoCapitalize="none"
            spellCheck={false}
            required
            disabled={request.isPending}
          />
        </label>
        <p className="login__error" role="alert">
          {request.isError && 'Could not request a code. Please try again.'}
        </p>
        <button className="login__submit" type="submit" disabled={request.isPending || username.trim() === ''}>
          {request.isPending ? 'Requesting…' : 'Request code'}
        </button>
        <button className="login__link" type="button" onClick={onBack}>
          Back to sign in
        </button>
      </form>
    </main>
  )
}

function ConfirmStep({
  username,
  onBack,
  onDone,
  onNewCode,
}: {
  username: string
  onBack: () => void
  onDone: () => void
  onNewCode: () => void
}) {
  const confirm = useConfirmPasswordReset()
  const [code, setCode] = useState('')
  const [next, setNext] = useState('')
  const [confirmation, setConfirmation] = useState('')
  const ref = useRef<HTMLInputElement>(null)
  useEffect(() => ref.current?.focus(), [])

  const rules = checkPassword(next, username, confirmation)
  const canSubmit = CODE_PATTERN.test(code) && isPasswordValid(next, username, confirmation) && !confirm.isPending
  const errors = confirm.isError ? confirmErrors(confirm.error) : null

  const onSubmit = (event: FormEvent) => {
    event.preventDefault()
    if (!canSubmit) return
    confirm.mutate({ username, code, newPassword: next }, { onSuccess: onDone })
  }

  return (
    <main className="login">
      <form className="login__card" onSubmit={onSubmit}>
        <h1 className="login__title">Enter your code</h1>
        <p className="reset__intro" role="status">
          {NEUTRAL_MESSAGE}
        </p>
        <label className="login__field">
          <span>6-digit code</span>
          <input
            ref={ref}
            className="reset__code"
            value={code}
            onChange={(e) => setCode(e.target.value.replace(/\D/g, '').slice(0, 6))}
            inputMode="numeric"
            autoComplete="one-time-code"
            maxLength={6}
            disabled={confirm.isPending}
          />
        </label>
        <label className="login__field">
          <span>New password</span>
          <input
            type="password"
            value={next}
            onChange={(e) => setNext(e.target.value)}
            autoComplete="new-password"
            disabled={confirm.isPending}
          />
        </label>
        <label className="login__field">
          <span>Confirm new password</span>
          <input
            type="password"
            value={confirmation}
            onChange={(e) => setConfirmation(e.target.value)}
            autoComplete="new-password"
            disabled={confirm.isPending}
          />
        </label>
        <ul className="reset__rules">
          {rules.map((rule) => (
            <li key={rule.id} className="reset__rule" data-met={rule.met}>
              <span aria-hidden="true" className="reset__mark">
                {rule.met ? '✓' : '○'}
              </span>
              {rule.label}
            </li>
          ))}
        </ul>
        <div className="login__error reset__errors" role="alert">
          {errors?.messages.map((message) => <p key={message}>{message}</p>)}
        </div>
        {errors?.invalidCode && (
          <button className="login__link" type="button" onClick={onNewCode}>
            Request a new code
          </button>
        )}
        <button className="login__submit" type="submit" disabled={!canSubmit}>
          {confirm.isPending ? 'Resetting…' : 'Reset password'}
        </button>
        <button className="login__link" type="button" onClick={onBack}>
          Back to sign in
        </button>
      </form>
    </main>
  )
}
