import { useEffect, useRef, useState, type FormEvent } from 'react'
import { Button, Card, Skeleton } from '../../shared/ui/index.ts'
import { ApiError } from '../../shared/api/client.ts'
import { isMfaRequired, useLogin, useOidcConfig, useVerifyTwoFactor } from './api.ts'
import { throttleMessage } from './throttleErrors.ts'
import { twoFactorErrorMessage } from './twoFactorErrors.ts'
import { AuthBrand } from './AuthBrand.tsx'
import { PasswordResetPage } from './PasswordResetPage.tsx'
import './LoginPage.css'

/** Browser-flow failure codes of the backend (`/?oidc_error=<CODE>`); anything else gets the generic text. */
const OIDC_ERRORS: Record<string, string> = {
  ROLE_DENIED: 'Your groups do not give you access to Shipir. Ask an administrator.',
  USER_NOT_PROVISIONED: 'No Shipir account is linked to this identity. Ask an administrator to create one.',
  USER_DISABLED: 'Your account is disabled. Ask an administrator.',
  PROVISIONING_FAILED: 'Your account could not be created. Ask an administrator.',
  PROVIDER_UNREACHABLE: 'The single sign-on provider could not be reached. Try again later.',
  PROVIDER_INVALID: 'The single sign-on provider answered unexpectedly. Ask an administrator.',
  OIDC_NOT_CONFIGURED: 'Single sign-on is not configured.',
  ENCRYPTION_KEY_MISSING: 'Single sign-on is not available. Ask an administrator.',
}
const OIDC_GENERIC_ERROR = 'Single sign-on failed. Please try again.'

function readOidcError(): string | null {
  try {
    const code = new URLSearchParams(window.location.search).get('oidc_error')
    if (code === null) return null
    window.history.replaceState(null, '', window.location.pathname)
    return OIDC_ERRORS[code] ?? OIDC_GENERIC_ERROR
  } catch {
    return null
  }
}

export function LoginPage() {
  const [view, setView] = useState<'login' | 'reset'>('login')
  const [notice, setNotice] = useState<string | null>(null)
  const [mfaPending, setMfaPending] = useState(false)
  const [problem, setProblem] = useState<string | null>(null)
  const [oidcError] = useState(readOidcError)

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
      oidcError={oidcError}
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
  oidcError,
  onForgot,
  onMfaRequired,
}: {
  notice: string | null
  problem: string | null
  oidcError: string | null
  onForgot: () => void
  onMfaRequired: () => void
}) {
  const login = useLogin()
  const oidcQuery = useOidcConfig()
  const oidc = oidcQuery.data
  const configPending = oidcQuery.isPending
  const [forceLocal, setForceLocal] = useState(false)
  const showOidc = oidc?.enabled === true
  const showLocal = !configPending && (!showOidc || oidc.localLoginEnabled || forceLocal)
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const usernameRef = useRef<HTMLInputElement>(null)
  const passwordRef = useRef<HTMLInputElement>(null)

  useEffect(() => {
    if (showLocal) usernameRef.current?.focus()
  }, [showLocal])

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
    : throttleMessage(login.error) ?? (login.error instanceof ApiError && login.error.status === 401
      ? 'Invalid username or password.'
      : login.error instanceof ApiError && login.error.code === 'LOCAL_LOGIN_DISABLED'
        ? 'Local login is disabled. Use single sign-on.'
      : 'Sign-in failed. Please try again.')

  return (
    <main className="login">
      <div className="auth-stack">
        <AuthBrand />
        <Card className="auth-card">
        <form className="login__card" onSubmit={onSubmit}>
        <h1 className="login__title">Sign in to Shipir</h1>
        {oidcError && (
          <p className="login__error" role="alert">
            {oidcError}
          </p>
        )}
        {configPending && <Skeleton height="var(--space-8)" />}
        {showOidc && (
          <Button
            className="login__submit"
            variant={showLocal ? 'secondary' : 'primary'}
            onClick={() => window.location.assign('/api/auth/oidc/authorize')}
          >
            Sign in with {oidc.displayName ?? 'single sign-on'}
          </Button>
        )}
        {showOidc && !showLocal && (
          <Button className="login__link" variant="ghost" size="sm" onClick={() => setForceLocal(true)}>
            Use local login
          </Button>
        )}
        {showLocal && (
          <>
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
          </>
        )}
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
