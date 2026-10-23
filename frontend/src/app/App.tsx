import { BrowserRouter, Route, Routes } from 'react-router-dom'
import { EnvironmentDashboard, EnvironmentsOverview } from '../features/hosts/index.ts'
import { AccountPage } from '../features/account/index.ts'
import { AuditLogPage, GitCredentialsSettingsPage, RetentionSettingsPage, WebhooksSettingsPage } from '../features/settings/index.ts'
import { ContainersPage } from '../features/containers/index.ts'
import { ImagesPage } from '../features/images/index.ts'
import { NetworksPage } from '../features/networks/index.ts'
import { VolumesPage } from '../features/volumes/index.ts'
import { StackDetailPage, StacksPage } from '../features/stacks/index.ts'
import { NotificationsPage } from '../features/notifications/index.ts'
import { LoginPage, SetupPage, useCurrentUser, useLogout } from '../features/auth/index.ts'
import { AppShell } from './layout/AppShell.tsx'
import { NotAllowedPage } from './layout/PlaceholderPages.tsx'
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

  const isAdmin = user.role === 'ADMIN'

  return (
    <AppShell user={user} onSignOut={() => logout.mutate()} signingOut={logout.isPending}>
      <Routes>
        <Route path="/" element={<EnvironmentsOverview role={user.role} />} />
        <Route path="/environments/:id" element={<EnvironmentDashboard />} />
        {/* One line per resource: later tasks swap the placeholder for the real list page. */}
        <Route path="/environments/:id/stacks" element={<StacksPage />} />
        <Route path="/environments/:id/stacks/:stackId" element={<StackDetailPage />} />
        <Route path="/environments/:id/containers" element={<ContainersPage />} />
        <Route path="/environments/:id/images" element={<ImagesPage />} />
        <Route path="/environments/:id/networks" element={<NetworksPage />} />
        <Route path="/environments/:id/volumes" element={<VolumesPage />} />
        <Route path="/notifications" element={<NotificationsPage />} />
        <Route path="/account" element={<AccountPage user={user} />} />
        <Route path="/settings/notifications" element={isAdmin ? <WebhooksSettingsPage /> : <NotAllowedPage />} />
        <Route path="/settings/retention" element={isAdmin ? <RetentionSettingsPage /> : <NotAllowedPage />} />
        <Route path="/settings/git" element={isAdmin ? <GitCredentialsSettingsPage /> : <NotAllowedPage />} />
        <Route path="/settings/audit-log" element={isAdmin ? <AuditLogPage /> : <NotAllowedPage />} />
        <Route path="/settings/*" element={<NotAllowedPage />} />
        <Route path="*" element={<EnvironmentsOverview role={user.role} />} />
      </Routes>
    </AppShell>
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
