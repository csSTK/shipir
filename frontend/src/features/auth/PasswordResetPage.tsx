import { useEffect, useId, useRef, useState, type FormEvent } from 'react'
import { Button, Card } from '../../shared/ui/index.ts'
import { ApiError } from '../../shared/api/client.ts'
import { AuthBrand } from './AuthBrand.tsx'
import { useConfirmPasswordReset, useRequestPasswordReset } from './api.ts'
import { checkPassword, isPasswordValid, violationMessage } from './passwordPolicy.ts'
import { throttleMessage } from './throttleErrors.ts'
import { fieldError } from './fieldError.ts'
import { useTranslation } from 'react-i18next'
import i18n from 'i18next'
import './LoginPage.css'
import './PasswordResetPage.css'

const NEUTRAL_MESSAGE = () => i18n.t('auth:PasswordResetPage.ifTheAccountExistsA')
const CODE_PATTERN = /^\d{6}$/

interface Props {
  onBack: () => void
  onDone: () => void
}

function confirmErrors(error: unknown): { invalidCode: boolean; policy: boolean; messages: string[] } {
  const throttled = throttleMessage(error)
  if (throttled) return { invalidCode: false, policy: false, messages: [throttled] }
  if (error instanceof ApiError && error.status === 400) {
    if (error.code === 'INVALID_RESET_CODE') {
      return { invalidCode: true, policy: false, messages: [i18n.t('auth:PasswordResetPage.theCodeIsInvalidOr')] }
    }
    if (error.code === 'PASSWORD_POLICY') {
      const violations = error.violations ?? []
      return {
        invalidCode: false,
        policy: true,
        messages: violations.length > 0 ? violations.map(violationMessage) : [i18n.t('auth:PasswordResetPage.thePasswordDoesNotMeet')],
      }
    }
  }
  return { invalidCode: false, policy: false, messages: [i18n.t('auth:PasswordResetPage.couldNotResetThePassword')] }
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
  const { t } = useTranslation('auth')
  const request = useRequestPasswordReset()
  const ref = useRef<HTMLInputElement>(null)
  const errorId = useId()
  useEffect(() => ref.current?.focus(), [])

  const onSubmit = (event: FormEvent) => {
    event.preventDefault()
    if (request.isPending || username.trim() === '') return
    request.mutate({ username: username.trim() }, { onSuccess: onRequested })
  }

  return (
    <main className="login">
      <div className="auth-stack">
        <AuthBrand />
        <Card className="auth-card">
        <form className="login__card" onSubmit={onSubmit}>
        <h1 className="login__title">{t('PasswordResetPage.resetYourPassword')}</h1>
        <p className="reset__intro">{t('PasswordResetPage.enterYourUsernameIfThe')}</p>
        <label className="login__field">
          <span>{t('PasswordResetPage.username')}</span>
          <input
            ref={ref}
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            autoComplete="username"
            autoCapitalize="none"
            spellCheck={false}
            required
            disabled={request.isPending}
            {...fieldError(errorId, request.isError && throttleMessage(request.error) === null)}
          />
        </label>
        <p id={errorId} className="login__error" role="alert">
          {request.isError && (throttleMessage(request.error) ?? t('PasswordResetPage.couldNotRequestACode'))}
        </p>
        <Button className="login__submit" variant="primary" type="submit" disabled={request.isPending || username.trim() === ''} loading={request.isPending}>
          {request.isPending ? t('PasswordResetPage.requesting') : t('PasswordResetPage.requestCode')}
        </Button>
        <Button className="login__link" variant="ghost" size="sm" onClick={onBack}>
          {t('PasswordResetPage.backToSignIn')}
        </Button>
      </form>
        </Card>
      </div>
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
  const { t } = useTranslation('auth')
  const confirm = useConfirmPasswordReset()
  const [code, setCode] = useState('')
  const [next, setNext] = useState('')
  const [confirmation, setConfirmation] = useState('')
  const ref = useRef<HTMLInputElement>(null)
  const errorId = useId()
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
      <div className="auth-stack">
        <AuthBrand />
        <Card className="auth-card">
        <form className="login__card" onSubmit={onSubmit}>
        <h1 className="login__title">{t('PasswordResetPage.enterYourCode')}</h1>
        <p className="reset__intro" role="status">
          {NEUTRAL_MESSAGE()}
        </p>
        <label className="login__field">
          <span>{t('PasswordResetPage.6DigitCode')}</span>
          <input
            ref={ref}
            className="reset__code"
            value={code}
            onChange={(e) => setCode(e.target.value.replace(/\D/g, '').slice(0, 6))}
            inputMode="numeric"
            autoComplete="one-time-code"
            maxLength={6}
            disabled={confirm.isPending}
            {...fieldError(errorId, errors?.invalidCode === true)}
          />
        </label>
        <label className="login__field">
          <span>{t('PasswordResetPage.newPassword')}</span>
          <input
            type="password"
            value={next}
            onChange={(e) => setNext(e.target.value)}
            autoComplete="new-password"
            disabled={confirm.isPending}
            {...fieldError(errorId, errors?.policy === true)}
          />
        </label>
        <label className="login__field">
          <span>{t('PasswordResetPage.confirmNewPassword')}</span>
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
        <div id={errorId} className="login__error reset__errors" role="alert">
          {errors?.messages.map((message) => <p key={message}>{message}</p>)}
        </div>
        {errors?.invalidCode && (
          <Button className="login__link" variant="ghost" size="sm" onClick={onNewCode}>
            {t('PasswordResetPage.requestANewCode')}
          </Button>
        )}
        <Button className="login__submit" variant="primary" type="submit" disabled={!canSubmit} loading={confirm.isPending}>
          {confirm.isPending ? t('PasswordResetPage.resetting') : t('PasswordResetPage.resetPassword')}
        </Button>
        <Button className="login__link" variant="ghost" size="sm" onClick={onBack}>
          {t('PasswordResetPage.backToSignIn')}
        </Button>
      </form>
        </Card>
      </div>
    </main>
  )
}
