import { useEffect, useId, useRef, useState, type FormEvent } from 'react'
import { Button, Card } from '../../shared/ui/index.ts'
import { ApiError } from '../../shared/api/client.ts'
import { useQueryClient } from '@tanstack/react-query'
import { EnvironmentForm } from '../hosts/index.ts'
import { AuthBrand } from './AuthBrand.tsx'
import { TwoFactorEnrollment } from './TwoFactorEnrollment.tsx'
import { CURRENT_USER_KEY, useChangePassword, type SetupStep } from './api.ts'
import { fieldError } from './fieldError.ts'
import { checkPassword, isPasswordValid, violationMessage } from './passwordPolicy.ts'
import { useTranslation } from 'react-i18next'
import i18n from 'i18next'
import './LoginPage.css'
import './SetupPage.css'

function errorMessages(error: unknown): string[] {
  if (error instanceof ApiError && error.status === 400) {
    if (error.code === 'INVALID_CURRENT_PASSWORD') return [i18n.t('auth:SetupPage.theCurrentPasswordIsIncorrect')]
    if (error.code === 'PASSWORD_POLICY') {
      const violations = error.violations ?? []
      return violations.length > 0
        ? violations.map(violationMessage)
        : [i18n.t('auth:SetupPage.thePasswordDoesNotMeet')]
    }
  }
  return [i18n.t('auth:SetupPage.couldNotSetThePassword')]
}

const STEPS: { id: SetupStep; label: string }[] = [
  { id: 'PASSWORD', get label() { return i18n.t('auth:SetupPage.password') } },
  { id: 'TWO_FACTOR', get label() { return i18n.t('auth:SetupPage.twoFactor') } },
  { id: 'ENVIRONMENT', get label() { return i18n.t('auth:SetupPage.environment') } },
]

function StepIndicator({ step }: { step: SetupStep }) {
  const { t } = useTranslation('auth')
  // Two-factor only appears once the server asks for it (an admin requirement).
  const steps = step === 'TWO_FACTOR' ? STEPS : STEPS.filter((s) => s.id !== 'TWO_FACTOR')
  return (
    <ol className="setup__steps" aria-label={t('SetupPage.setupSteps')}>
      {steps.map(({ id, label }, index) => {
        const current = steps.findIndex((s) => s.id === step)
        return (
          <li
            key={id}
            className="setup__step"
            data-state={index < current ? 'done' : index === current ? 'current' : 'todo'}
            aria-current={index === current ? 'step' : undefined}
          >
            <span aria-hidden="true" className="setup__step-number">
              {index < current ? '✓' : index + 1}
            </span>
            {label}
          </li>
        )
      })}
    </ol>
  )
}

/** Admins must enrol a second factor before the app opens when the server requires it. */
function TwoFactorStep() {
  const { t } = useTranslation('auth')
  const queryClient = useQueryClient()
  return (
    <Card className="setup__card setup__panel auth-card">
      <h1 className="setup__title">{t('SetupPage.setUpTwoFactorAuthentication')}</h1>
      <p className="setup__intro">{t('SetupPage.anAdministratorRequiresTwoFactor')}</p>
      <TwoFactorEnrollment
        autoStart
        finishLabel={t('SetupPage.continue')}
        onFinished={() => void queryClient.refetchQueries({ queryKey: CURRENT_USER_KEY })}
      />
    </Card>
  )
}

/** Second first-start step: an admin adds the first Docker environment before the app opens. */
function EnvironmentStep() {
  const { t } = useTranslation('auth')
  const queryClient = useQueryClient()
  const [finishing, setFinishing] = useState(false)
  return (
    <Card className="setup__card setup__panel auth-card">
      <h1 className="setup__title">{t('SetupPage.addYourFirstEnvironment')}</h1>
      <p className="setup__intro">
        {t('SetupPage.anEnvironmentIsADocker')}
      </p>
      <EnvironmentForm
        submitLabel={t('SetupPage.continue')}
        onCreated={() => {
          setFinishing(true)
          // The gate leaves the setup once /auth/me reports no pending step.
          void queryClient.refetchQueries({ queryKey: CURRENT_USER_KEY })
        }}
      />
      <div className="setup__success" role="status">
        {finishing && t('SetupPage.environmentAddedOpeningShipir')}
      </div>
    </Card>
  )
}

/** Forced first-start flow: replace the initial password, then add the first environment. */
export function SetupPage({ username, step = 'PASSWORD' }: { username: string; step?: SetupStep }) {
  return (
    <main className="setup">
      <div className="auth-stack">
        <AuthBrand />
        <StepIndicator step={step} />
        {step === 'ENVIRONMENT' ? (
          <EnvironmentStep key="environment" />
        ) : step === 'TWO_FACTOR' ? (
          <TwoFactorStep key="two-factor" />
        ) : (
          <PasswordStep key="password" username={username} />
        )}
      </div>
    </main>
  )
}

function PasswordStep({ username }: { username: string }) {
  const { t } = useTranslation('auth')
  const changePassword = useChangePassword()
  const [current, setCurrent] = useState('')
  const [next, setNext] = useState('')
  const [confirmation, setConfirmation] = useState('')
  const currentRef = useRef<HTMLInputElement>(null)
  const errorId = useId()
  const errorCode = changePassword.error instanceof ApiError ? changePassword.error.code : undefined

  useEffect(() => {
    currentRef.current?.focus()
  }, [])

  const rules = checkPassword(next, username, confirmation)
  const canSubmit =
    current.length > 0 && isPasswordValid(next, username, confirmation) && !changePassword.isPending

  const onSubmit = (event: FormEvent) => {
    event.preventDefault()
    if (!canSubmit) return
    changePassword.mutate(
      { currentPassword: current, newPassword: next },
      {
        onError: (error) => {
          if (error instanceof ApiError && error.code === 'INVALID_CURRENT_PASSWORD') {
            setCurrent('')
            currentRef.current?.focus()
          }
        },
      },
    )
  }

  return (
    <>
      <Card className="setup__panel auth-card">
      <form className="setup__card" onSubmit={onSubmit}>
        <h1 className="setup__title">{t('SetupPage.setYourPassword')}</h1>
        <p className="setup__intro">
          {t('SetupPage.thisIsTheFirstStart')}
        </p>
        <label className="setup__field">
          <span>{t('SetupPage.currentPassword')}</span>
          <input
            ref={currentRef}
            type="password"
            value={current}
            onChange={(e) => setCurrent(e.target.value)}
            autoComplete="current-password"
            disabled={changePassword.isPending}
            {...fieldError(errorId, errorCode === 'INVALID_CURRENT_PASSWORD')}
          />
        </label>
        <label className="setup__field">
          <span>{t('SetupPage.newPassword')}</span>
          <input
            type="password"
            value={next}
            onChange={(e) => setNext(e.target.value)}
            autoComplete="new-password"
            disabled={changePassword.isPending}
            {...fieldError(errorId, errorCode === 'PASSWORD_POLICY')}
          />
        </label>
        <label className="setup__field">
          <span>{t('SetupPage.confirmNewPassword')}</span>
          <input
            type="password"
            value={confirmation}
            onChange={(e) => setConfirmation(e.target.value)}
            autoComplete="new-password"
            disabled={changePassword.isPending}
          />
        </label>
        <ul className="setup__rules">
          {rules.map((rule) => (
            <li key={rule.id} className="setup__rule" data-met={rule.met}>
              <span aria-hidden="true" className="setup__mark">
                {rule.met ? '✓' : '○'}
              </span>
              {rule.label}
            </li>
          ))}
        </ul>
        <div id={errorId} className="setup__error" role="alert">
          {changePassword.isError &&
            errorMessages(changePassword.error).map((message) => <p key={message}>{message}</p>)}
        </div>
        <div className="setup__success" role="status">
          {changePassword.isSuccess && t('SetupPage.passwordSetOpeningShipir')}
        </div>
        <Button className="setup__submit" variant="primary" type="submit" disabled={!canSubmit} loading={changePassword.isPending}>
          {changePassword.isPending ? t('SetupPage.saving') : t('SetupPage.setPassword')}
        </Button>
      </form>
      </Card>
    </>
  )
}
