import { LoginPage, SetupPage, useCurrentUser, useLogout } from '../features/auth/index.ts'
import { Providers } from './providers.tsx'

function AuthGate() {
  const { data: user, isPending, isError } = useCurrentUser()
  const logout = useLogout()

  if (isPending) return <div aria-busy="true" />
  if (isError) return <p role="alert">Could not reach the server.</p>
  if (user === null) return <LoginPage />
  if (user.setupRequired) return <SetupPage username={user.username} />

  return (
    <main>
      <h1>Shipir</h1>
      <p>Manage Docker containers and Compose stacks.</p>
      <button type="button" onClick={() => logout.mutate()} disabled={logout.isPending}>
        Sign out
      </button>
    </main>
  )
}

export function App() {
  return (
    <Providers>
      <AuthGate />
    </Providers>
  )
}
