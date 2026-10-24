import { useEffect, useRef, useState, type FormEvent } from 'react'
import { Button, Card } from '../../shared/ui/index.ts'
import { ApiError } from '../../shared/api/client.ts'
import { useLogin } from './api.ts'
import { AuthBrand } from './AuthBrand.tsx'
import { PasswordResetPage } from './PasswordResetPage.tsx'
import './LoginPage.css'

export function LoginPage() {
  const [view, setView] = useState<'login' | 'reset'>('login')
  const [notice, setNotice] = useState<string | null>(null)

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
      onForgot={() => {
        setNotice(null)
        setView('reset')
      }}
    />
  )
}

function SignInForm({ notice, onForgot }: { notice: string | null; onForgot: () => void }) {
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
        onError: () => {
          setPassword('')
          passwordRef.current?.focus()
        },
      },
    )
  }

  const errorMessage = !login.isError
    ? null
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
