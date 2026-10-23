import { BrowserRouter, Route, Routes } from 'react-router-dom'
import { ENV_RESOURCES, EnvironmentDashboard, EnvironmentsOverview } from '../features/hosts/index.ts'
import { NotificationsPage } from '../features/notifications/index.ts'
import { LoginPage, SetupPage, useCurrentUser, useLogout } from '../features/auth/index.ts'
import { AppShell } from './layout/AppShell.tsx'
import { NotAllowedPage, PlaceholderPage } from './layout/PlaceholderPages.tsx'
import { Providers } from './providers.tsx'

function ResourcePlaceholder({ slug }: { slug: (typeof ENV_RESOURCES)[number]['slug'] }) {
  const label = ENV_RESOURCES.find((r) => r.slug === slug)?.label ?? slug
  return <PlaceholderPage title={label} />
}

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
  const settings = (title: string) =>
    isAdmin ? <PlaceholderPage title={title} /> : <NotAllowedPage />

  return (
    <AppShell user={user} onSignOut={() => logout.mutate()} signingOut={logout.isPending}>
      <Routes>
        <Route path="/" element={<EnvironmentsOverview role={user.role} />} />
        <Route path="/environments/:id" element={<EnvironmentDashboard />} />
        {/* One line per resource: later tasks swap the placeholder for the real list page. */}
        <Route path="/environments/:id/stacks" element={<ResourcePlaceholder slug="stacks" />} />
        <Route path="/environments/:id/containers" element={<ResourcePlaceholder slug="containers" />} />
        <Route path="/environments/:id/images" element={<ResourcePlaceholder slug="images" />} />
        <Route path="/environments/:id/networks" element={<ResourcePlaceholder slug="networks" />} />
        <Route path="/environments/:id/volumes" element={<ResourcePlaceholder slug="volumes" />} />
        <Route path="/notifications" element={<NotificationsPage />} />
        <Route path="/account" element={<PlaceholderPage title="Account" />} />
        <Route path="/settings/notifications" element={settings('Notification settings')} />
        <Route path="/settings/retention" element={settings('Retention')} />
        <Route path="/settings/audit-log" element={settings('Audit log')} />
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
