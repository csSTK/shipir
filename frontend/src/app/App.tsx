import { BrowserRouter, Navigate, Route, Routes, useMatch, useParams } from 'react-router-dom'
import { EnvironmentDashboard, EnvironmentsOverview } from '../features/hosts/index.ts'
import { AccountPage } from '../features/account/index.ts'
import { AiSettingsPage, AuditLogPage, BackupSettingsPage, GitCredentialsSettingsPage, OidcSettingsPage, RegistriesSettingsPage, RetentionSettingsPage, SharedChatSessionsPage, TeamsSettingsPage, UsersSettingsPage, WebhooksSettingsPage } from '../features/settings/index.ts'
import { ChatPage } from '../features/ai-chat/index.ts'
import { ContainersPage, ContainerLogsPage, ContainerConsolePage, ContainerDetailsPage, useConsoleAccess } from '../features/containers/index.ts'
import { ImagesPage } from '../features/images/index.ts'
import { NetworksPage } from '../features/networks/index.ts'
import { VolumesPage } from '../features/volumes/index.ts'
import { DeploymentDetailPage, DeploymentsPage } from '../features/deployments/index.ts'
import { NotificationsPage } from '../features/notifications/index.ts'
import { LoginPage, SetupPage, useCurrentUser, useLogout } from '../features/auth/index.ts'
import { AppShell } from './layout/AppShell.tsx'
import { NotAllowedPage } from './layout/PlaceholderPages.tsx'
import { useHostEvents } from '../shared/realtime/useHostEvents.ts'
import { Providers } from './providers.tsx'
import { Fragment, useLayoutEffect, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { applyLanguage, resolveLanguage } from '../shared/i18n/index.ts'

/** Subscribes to docker events of the environment in the URL; leaving `/environments/:id/*` unsubscribes. */
function HostEventsBridge() {
  useHostEvents(useMatch('/environments/:id/*')?.params.id)
  return null
}

/** Old `/environments/:id/stacks[/:deploymentId]` URLs forward to `/deployments`. */
function LegacyStacksRedirect() {
  const { id, deploymentId } = useParams()
  return <Navigate to={`/environments/${id}/deployments${deploymentId ? `/${deploymentId}` : ''}`} replace />
}

/** Console page for ADMIN and, while operator exec is enabled, OPERATOR; the server still checks per-container permission. */
function ConsoleRoute() {
  const { allowed, pending } = useConsoleAccess()
  if (pending) return <div aria-busy="true" />
  return allowed ? <ContainerConsolePage /> : <NotAllowedPage />
}

function AuthGate() {
  const { t } = useTranslation('app')
  const { data: user, isPending, isError } = useCurrentUser()
  const logout = useLogout()
  // The user's language (or the browser's while none is chosen) applies before the first paint. Without a
  // session (login, reset, setup, after logout) it is the browser language, never the previous user's.
  const locale = user?.locale
  const settled = !isPending && !isError
  useLayoutEffect(() => {
    if (settled) applyLanguage(resolveLanguage(locale))
  }, [settled, locale])

  if (isPending) return <div aria-busy="true" />
  if (isError) return <p role="alert">{t('App.couldNotReachTheServer')}</p>
  if (user === null) return <LoginPage />
  // setupStep is authoritative; older responses only carry setupRequired (= password step).
  const setupStep = user.setupStep ?? (user.setupRequired ? 'PASSWORD' : null)
  if (setupStep) return <SetupPage username={user.username} step={setupStep} />

  const isAdmin = user.role === 'ADMIN'

  return (
    <AppShell user={user} onSignOut={() => logout.mutate()} signingOut={logout.isPending}>
      <HostEventsBridge />
      <Routes>
        <Route path="/" element={<EnvironmentsOverview role={user.role} />} />
        <Route path="/environments/:id" element={<EnvironmentDashboard />} />
        {/* One line per resource: later tasks swap the placeholder for the real list page. */}
        <Route path="/environments/:id/deployments" element={<DeploymentsPage />} />
        <Route path="/environments/:id/deployments/:deploymentId" element={<DeploymentDetailPage />} />
        {/* Bookmarks from before the stack -> deployment rename. */}
        <Route path="/environments/:id/stacks" element={<LegacyStacksRedirect />} />
        <Route path="/environments/:id/stacks/:deploymentId" element={<LegacyStacksRedirect />} />
        <Route path="/environments/:id/containers" element={<ContainersPage />} />
        <Route path="/environments/:id/containers/:cid" element={<ContainerDetailsPage />} />
        <Route path="/environments/:id/containers/:cid/logs" element={<ContainerLogsPage />} />
        <Route path="/environments/:id/containers/:cid/console" element={<ConsoleRoute />} />
        <Route path="/environments/:id/images" element={<ImagesPage />} />
        <Route path="/environments/:id/networks" element={<NetworksPage />} />
        <Route path="/environments/:id/volumes" element={<VolumesPage />} />
        <Route path="/assistant" element={<ChatPage role={user.role} />} />
        <Route path="/assistant/:sessionId" element={<ChatPage role={user.role} />} />
        <Route path="/notifications" element={<NotificationsPage />} />
        <Route path="/account" element={<AccountPage user={user} />} />
        <Route path="/settings/notifications" element={isAdmin ? <WebhooksSettingsPage /> : <NotAllowedPage />} />
        <Route path="/settings/retention" element={isAdmin ? <RetentionSettingsPage /> : <NotAllowedPage />} />
        <Route path="/settings/users" element={isAdmin ? <UsersSettingsPage currentUsername={user.username} /> : <NotAllowedPage />} />
        <Route path="/settings/teams" element={isAdmin ? <TeamsSettingsPage /> : <NotAllowedPage />} />
        <Route path="/settings/git" element={isAdmin ? <GitCredentialsSettingsPage /> : <NotAllowedPage />} />
        <Route path="/settings/registries" element={isAdmin ? <RegistriesSettingsPage /> : <NotAllowedPage />} />
        <Route path="/settings/oidc" element={isAdmin ? <OidcSettingsPage /> : <NotAllowedPage />} />
        <Route path="/settings/ai" element={isAdmin ? <AiSettingsPage /> : <NotAllowedPage />} />
        <Route path="/settings/ai/shared" element={isAdmin ? <SharedChatSessionsPage /> : <NotAllowedPage />} />
        <Route path="/settings/audit-log" element={isAdmin ? <AuditLogPage /> : <NotAllowedPage />} />
        <Route path="/settings/backup" element={isAdmin ? <BackupSettingsPage /> : <NotAllowedPage />} />
        <Route path="/settings/*" element={<NotAllowedPage />} />
        <Route path="*" element={<EnvironmentsOverview role={user.role} />} />
      </Routes>
    </AppShell>
  )
}

/**
 * Remounts the app tree when the language changes. Texts read through `i18n.t` in memoised column definitions
 * and chart builders would otherwise keep the old language, and a remount is the one approach that cannot
 * miss a spot. Server state lives in the QueryClient above, so nothing is refetched. The Account page
 * gives the focus back to its language select.
 */
function LanguageBoundary({ children }: { children: ReactNode }) {
  const { i18n } = useTranslation()
  return <Fragment key={i18n.language}>{children}</Fragment>
}

export function App() {
  return (
    <Providers>
      <BrowserRouter>
        <LanguageBoundary>
          <AuthGate />
        </LanguageBoundary>
      </BrowserRouter>
    </Providers>
  )
}
