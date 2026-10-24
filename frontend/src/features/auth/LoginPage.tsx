import { useEffect, useRef, useState, type FormEvent } from 'react'
import { Button, Card } from '../../shared/ui/index.ts'
import { ApiError } from '../../shared/api/client.ts'
import { isMfaRequired, useLogin, useVerifyTwoFactor } from './api.ts'
import { twoFactorErrorMessage } from './twoFactorErrors.ts'
import { AuthBrand } from './AuthBrand.tsx'
import { PasswordResetPage } from './PasswordResetPage.tsx'
import './LoginPage.css'

export function LoginPage() {
  const [view, setView] = useState<'login' | 'reset'>('login')
  const [notice, setNotice] = useState<string | null>(null)
  const [mfaPending, setMfaPending] = useState(false)
  const [problem, setProblem] = useState<string | null>(null)

  if (mfaPending) {
    return (
      <CodeStep
        onBack={(message) => {
          setProblem(message)
          setMfaPending(false)
        }}
      />
    )
  }
  if (view === 'reset') {
    return (
      <PasswordResetPage
        onBack={() => setView('login')}
        onDone={() => {
          setNotice('Your password was reset. Sign in with the new password.')
          setView('login')
        }}
      />
    )
  }
  return (
    <SignInForm
      notice={notice}
      problem={problem}
      onMfaRequired={() => setMfaPending(true)}
      onForgot={() => {
        setNotice(null)
        setView('reset')
      }}
    />
  )
}

function SignInForm({
  notice,
  problem,
  onForgot,
  onMfaRequired,
}: {
  notice: string | null
  problem: string | null
  onForgot: () => void
  onMfaRequired: () => void
}) {
  const login = useLogin()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const usernameRef = useRef<HTMLInputElement>(null)
  const passwordRef = useRef<HTMLInputElement>(null)

  useEffect(() => {
    usernameRef.current?.focus()
  }, [])

  const onSubmit = (event: FormEvent) => {
    event.preventDefault()
    if (login.isPending) return
    login.mutate(
      { username, password },
      {
        onSuccess: (result) => {
          if (isMfaRequired(result)) onMfaRequired()
        },
        onError: () => {
          setPassword('')
          passwordRef.current?.focus()
        },
      },
    )
  }

  const errorMessage = !login.isError
    ? problem
    : login.error instanceof ApiError && login.error.status === 401
      ? 'Invalid username or password.'
      : 'Sign-in failed. Please try again.'

  return (
    <main className="login">
      <div className="auth-stack">
        <AuthBrand />
        <Card className="auth-card">
        <form className="login__card" onSubmit={onSubmit}>
        <h1 className="login__title">Sign in to Shipir</h1>
        {notice && (
          <p className="login__notice" role="status">
            {notice}
          </p>
        )}
        <label className="login__field">
          <span>Username</span>
          <input
            ref={usernameRef}
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            autoComplete="username"
            autoCapitalize="none"
            spellCheck={false}
            required
            disabled={login.isPending}
          />
        </label>
        <label className="login__field">
          <span>Password</span>
          <input
            ref={passwordRef}
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete="current-password"
            required
            disabled={login.isPending}
          />
        </label>
        <p className="login__error" role="alert">
          {errorMessage}
        </p>
        <Button className="login__submit" variant="primary" type="submit" disabled={login.isPending} loading={login.isPending}>
          {login.isPending ? 'Signing in…' : 'Sign in'}
        </Button>
        <Button className="login__link" variant="ghost" size="sm" onClick={onForgot}>
          Forgot password?
        </Button>
        </form>
        </Card>
      </div>
    </main>
  )
}

/** Second sign-in step for users with 2FA: authenticator code or a recovery code. */
function CodeStep({ onBack }: { onBack: (notice: string | null) => void }) {
  const verify = useVerifyTwoFactor()
  const [code, setCode] = useState('')
  const codeRef = useRef<HTMLInputElement>(null)

  useEffect(() => {
    codeRef.current?.focus()
  }, [])

  const onSubmit = (event: FormEvent) => {
    event.preventDefault()
    if (verify.isPending || code.trim() === '') return
    verify.mutate(code.trim(), {
      onError: (error) => {
        if (error instanceof ApiError && error.code === 'UNAUTHENTICATED') {
          onBack('Your sign-in expired. Enter your username and password again.')
          return
        }
        setCode('')
        codeRef.current?.focus()
      },
    })
  }

  return (
    <main className="login">
      <div className="auth-stack">
        <AuthBrand />
        <Card className="auth-card">
          <form className="login__card" onSubmit={onSubmit}>
            <h1 className="login__title">Two-factor authentication</h1>
            <p className="login__hint">Enter the 6-digit code from your authenticator app, or one of your recovery codes.</p>
            <label className="login__field">
              <span>Authentication code</span>
              <input
                ref={codeRef}
                value={code}
                onChange={(e) => setCode(e.target.value)}
                autoComplete="one-time-code"
                inputMode="text"
                autoCapitalize="characters"
                spellCheck={false}
                disabled={verify.isPending}
              />
            </label>
            <p className="login__error" role="alert">
              {verify.isError && twoFactorErrorMessage(verify.error, 'Verification failed. Please try again.')}
            </p>
            <Button className="login__submit" variant="primary" type="submit" disabled={verify.isPending} loading={verify.isPending}>
              Verify
            </Button>
            <Button className="login__link" variant="ghost" size="sm" onClick={() => onBack(null)}>
              Back to sign in
            </Button>
          </form>
        </Card>
      </div>
    </main>
  )
}
