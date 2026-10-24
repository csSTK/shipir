import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../../shared/api/client.ts'
import { ContainerDetailsPage } from './ContainerDetailsPage.tsx'

let role = 'ADMIN'
const apiFetch = vi.fn()
vi.mock('../../../shared/api/client.ts', async (importActual) => ({
  ...(await importActual<typeof import('../../../shared/api/client.ts')>()),
  apiFetch: (...args: unknown[]) => apiFetch(...args),
}))

afterEach(() => {
  cleanup()
  apiFetch.mockReset()
})

const details = {
  id: 'c1',
  name: 'web',
  image: 'nginx:1',
  state: 'running',
  created: '2026-10-01T10:00:00Z',
  startedAt: '2026-10-01T10:00:05Z',
  restartCount: 2,
  restartPolicy: { name: 'on-failure', maxRetries: 3 },
  command: ['nginx', '-g', 'daemon off;'],
  env: [
    { key: 'DB_PASSWORD', value: 's3cret' },
    { key: 'MODE', value: 'prod' },
  ],
  mounts: [{ type: 'bind', name: null, source: '/srv/data', destination: '/data', readOnly: true }],
  labels: { 'com.docker.compose.project': 'shop' },
  networks: [{ name: 'shop_default', ipAddress: '172.18.0.2', gateway: '172.18.0.1', macAddress: '02:42', aliases: ['web'] }],
  ports: [{ containerPort: 80, protocol: 'tcp', hostIp: '0.0.0.0', hostPort: 8080 }],
  health: { status: 'unhealthy', failingStreak: 3, recentLogs: [{ start: '2026-10-01T10:01:00Z', end: '2026-10-01T10:01:01Z', exitCode: 1, output: 'curl: refused' }] },
  composeProject: 'shop',
  composeService: 'web',
}

function renderPage(respond: () => unknown) {
  apiFetch.mockImplementation((path: string) => {
    if (path === '/auth/me') return Promise.resolve({ username: 'u', role, setupRequired: false })
    return Promise.resolve().then(respond)
  })
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter initialEntries={['/environments/h1/containers/c1']}>
        <Routes>
          <Route path="/environments/:id/containers/:cid" element={<ContainerDetailsPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('ContainerDetailsPage', () => {
  it('shows overview, mounts, network, labels and health', async () => {
    role = 'ADMIN'
    renderPage(() => details)
    expect(await screen.findByRole('heading', { name: 'web' })).toBeInTheDocument()
    expect(screen.getByText('nginx:1')).toBeInTheDocument()
    expect(screen.getByText('nginx -g daemon off;')).toBeInTheDocument()
    expect(screen.getByText('on-failure (max 3)')).toBeInTheDocument()
    expect(screen.getByText('/srv/data')).toBeInTheDocument()
    expect(screen.getByText('read-only')).toBeInTheDocument()
    expect(screen.getByText('172.18.0.2')).toBeInTheDocument()
    expect(screen.getByText('8080 → 80/tcp')).toBeInTheDocument()
    expect(screen.getByText('com.docker.compose.project')).toBeInTheDocument()
    expect(screen.getByText('unhealthy')).toBeInTheDocument()
    expect(screen.getByText('curl: refused')).toBeInTheDocument()
  })

  it('masks env values until revealed', async () => {
    role = 'ADMIN'
    renderPage(() => details)
    await screen.findByRole('heading', { name: 'web' })
    expect(screen.getByText('DB_PASSWORD')).toBeInTheDocument()
    expect(screen.queryByText('s3cret')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Reveal values' }))
    expect(screen.getByText('s3cret')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Hide values' }))
    expect(screen.queryByText('s3cret')).not.toBeInTheDocument()
  })

  it('shows hidden for viewers without a reveal button', async () => {
    role = 'VIEWER'
    renderPage(() => ({ ...details, env: [{ key: 'DB_PASSWORD', value: null }] }))
    await screen.findByRole('heading', { name: 'web' })
    expect(screen.queryByRole('button', { name: 'Reveal values' })).not.toBeInTheDocument()
    expect(screen.getByText('Hidden for your role')).toBeInTheDocument()
  })

  it('links to logs and offers console only to admins', async () => {
    role = 'OPERATOR'
    renderPage(() => details)
    const logs = await screen.findByRole('link', { name: 'Logs' })
    expect(logs).toHaveAttribute('href', '/environments/h1/containers/c1/logs')
    expect(screen.queryByRole('link', { name: 'Console' })).not.toBeInTheDocument()
    cleanup()
    role = 'ADMIN'
    renderPage(() => details)
    expect(await screen.findByRole('link', { name: 'Console' })).toHaveAttribute('href', '/environments/h1/containers/c1/console')
  })

  it('omits health when the container has no healthcheck', async () => {
    renderPage(() => ({ ...details, health: null }))
    await screen.findByRole('heading', { name: 'web' })
    expect(within(document.body).queryByRole('heading', { name: 'Health' })).not.toBeInTheDocument()
  })

  it('shows a loading state, then not-found', async () => {
    renderPage(() => {
      throw new ApiError(404, 'x', 'RESOURCE_NOT_FOUND')
    })
    expect(screen.getByText('Loading…')).toBeInTheDocument()
    expect(await screen.findByText('This container no longer exists.')).toBeInTheDocument()
  })

  it('shows an error with retry', async () => {
    let fail = true
    renderPage(() => {
      if (fail) throw new ApiError(502, 'x', 'UNREACHABLE')
      return details
    })
    expect(await screen.findByRole('alert')).toBeInTheDocument()
    fail = false
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }))
    expect(await screen.findByRole('heading', { name: 'web' })).toBeInTheDocument()
  })
})
