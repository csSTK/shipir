import { useEffect, useRef, useState, type FormEvent } from 'react'
import { ApiError } from '../../shared/api/client.ts'
import { useQueryClient } from '@tanstack/react-query'
import { EnvironmentForm } from '../hosts/index.ts'
import { CURRENT_USER_KEY, useChangePassword, type SetupStep } from './api.ts'
import { checkPassword, isPasswordValid, violationMessage } from './passwordPolicy.ts'
import './SetupPage.css'

function errorMessages(error: unknown): string[] {
  if (error instanceof ApiError && error.status === 400) {
    if (error.code === 'INVALID_CURRENT_PASSWORD') return ['The current password is incorrect.']
    if (error.code === 'PASSWORD_POLICY') {
      const violations = error.violations ?? []
      return violations.length > 0
        ? violations.map(violationMessage)
        : ['The password does not meet the requirements.']
    }
  }
  return ['Could not set the password. Please try again.']
}

const STEPS: { id: SetupStep; label: string }[] = [
  { id: 'PASSWORD', label: 'Password' },
  { id: 'ENVIRONMENT', label: 'Environment' },
]

function StepIndicator({ step }: { step: SetupStep }) {
  return (
    <ol className="setup__steps" aria-label="Setup steps">
      {STEPS.map(({ id, label }, index) => {
        const current = STEPS.findIndex((s) => s.id === step)
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

/** Second first-start step: an admin adds the first Docker environment before the app opens. */
function EnvironmentStep() {
  const queryClient = useQueryClient()
  const [finishing, setFinishing] = useState(false)
  return (
    <div className="setup__card setup__panel">
      <h1 className="setup__title">Add your first environment</h1>
      <p className="setup__intro">
        An environment is a Docker engine Shipir manages: this machine or a remote host. You can add more
        later.
      </p>
      <EnvironmentForm
        submitLabel="Continue"
        onCreated={() => {
          setFinishing(true)
          // The gate leaves the setup once /auth/me reports no pending step.
          void queryClient.refetchQueries({ queryKey: CURRENT_USER_KEY })
        }}
      />
      <div className="setup__success" role="status">
        {finishing && 'Environment added. Opening Shipir…'}
      </div>
    </div>
  )
}

/** Forced first-start flow: replace the initial password, then add the first environment. */
export function SetupPage({ username, step = 'PASSWORD' }: { username: string; step?: SetupStep }) {
  return (
    <main className="setup">
      <div className="setup__wrap">
        <StepIndicator step={step} />
        {step === 'ENVIRONMENT' ? <EnvironmentStep key="environment" /> : <PasswordStep key="password" username={username} />}
      </div>
    </main>
  )
}

function PasswordStep({ username }: { username: string }) {
  const changePassword = useChangePassword()
  const [current, setCurrent] = useState('')
  const [next, setNext] = useState('')
  const [confirmation, setConfirmation] = useState('')
  const currentRef = useRef<HTMLInputElement>(null)

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
      <form className="setup__card setup__panel" onSubmit={onSubmit}>
        <h1 className="setup__title">Set your password</h1>
        <p className="setup__intro">
          This is the first start of Shipir. The initial password is known to whoever installed it, so
          replace it with one only you know before you continue.
        </p>
        <label className="setup__field">
          <span>Current password</span>
          <input
            ref={currentRef}
            type="password"
            value={current}
            onChange={(e) => setCurrent(e.target.value)}
            autoComplete="current-password"
            disabled={changePassword.isPending}
          />
        </label>
        <label className="setup__field">
          <span>New password</span>
          <input
            type="password"
            value={next}
            onChange={(e) => setNext(e.target.value)}
            autoComplete="new-password"
            disabled={changePassword.isPending}
          />
        </label>
        <label className="setup__field">
          <span>Confirm new password</span>
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
        <div className="setup__error" role="alert">
          {changePassword.isError &&
            errorMessages(changePassword.error).map((message) => <p key={message}>{message}</p>)}
        </div>
        <div className="setup__success" role="status">
          {changePassword.isSuccess && 'Password set. Opening Shipir…'}
        </div>
        <button className="setup__submit" type="submit" disabled={!canSubmit}>
          {changePassword.isPending ? 'Saving…' : 'Set password'}
        </button>
      </form>
    </>
  )
}
