import { BrowserRouter, Route, Routes } from 'react-router-dom'
import { EnvironmentPage, EnvironmentsOverview } from '../features/hosts/index.ts'
import { LoginPage, SetupPage, useCurrentUser, useLogout } from '../features/auth/index.ts'
import { Providers } from './providers.tsx'

function AuthGate() {
  const { data: user, isPending, isError } = useCurrentUser()
  const logout = useLogout()

  if (isPending) return <div aria-busy="true" />
  if (isError) return <p role="alert">Could not reach the server.</p>
  if (user === null) return <LoginPage />
  // setupStep is authoritative; older responses only carry setupRequired (= password step).
  const setupStep = user.setupStep ?? (user.setupRequired ? 'PASSWORD' : null)
  if (setupStep) return <SetupPage username={user.username} step={setupStep} />

  return (
    <main>
      <h1>Shipir</h1>
      <p>Manage Docker containers and Compose stacks.</p>
      <Routes>
        <Route path="/" element={<EnvironmentsOverview role={user.role} />} />
        <Route path="/environments/:id" element={<EnvironmentPage />} />
        <Route path="*" element={<EnvironmentsOverview role={user.role} />} />
      </Routes>
      <button type="button" onClick={() => logout.mutate()} disabled={logout.isPending}>
        Sign out
      </button>
    </main>
  )
}

export function App() {
  return (
    <Providers>
      <BrowserRouter>
        <AuthGate />
      </BrowserRouter>
    </Providers>
  )
}
