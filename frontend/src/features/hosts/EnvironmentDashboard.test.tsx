import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { EnvironmentDashboard } from './EnvironmentDashboard.tsx'
import type { HostOverview } from './api.ts'

const apiFetch = vi.fn()
vi.mock('../../shared/api/client.ts', async (importActual) => ({
  ...(await importActual<typeof import('../../shared/api/client.ts')>()),
  apiFetch: (...args: unknown[]) => apiFetch(...args),
}))

afterEach(() => {
  cleanup()
  apiFetch.mockReset()
})

const up: HostOverview = {
  id: 'a',
  name: 'Local',
  connectionType: 'LOCAL',
  endpoint: 'unix:///var/run/docker.sock',
  status: 'UP',
  error: null,
  dockerVersion: '27.1.2',
  cpus: 8,
  memoryBytes: 16 * 1024 ** 3,
  containers: { running: 5, stopped: 2, healthy: 3, unhealthy: 1 },
  images: 12,
  volumes: 4,
}
const down: HostOverview = {
  ...up,
  id: 'b',
  name: 'Staging',
  status: 'DOWN',
  error: 'TIMEOUT',
  dockerVersion: null,
  containers: null,
  images: null,
  volumes: null,
}

function renderAt(id: string) {
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter initialEntries={[`/environments/${id}`]}>
        <Routes>
          <Route path="/environments/:id" element={<EnvironmentDashboard />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('EnvironmentDashboard', () => {
  it('shows status, version and tiles linking to the resource pages', async () => {
    apiFetch.mockResolvedValue([up, down])
    renderAt('a')
    expect(await screen.findByRole('heading', { name: 'Local' })).toBeInTheDocument()
    expect(screen.getByText('Up')).toBeInTheDocument()
    expect(screen.getByText(/Docker 27\.1\.2/)).toBeInTheDocument()
    const containers = screen.getByRole('link', { name: /Containers/ })
    expect(containers).toHaveTextContent('7')
    expect(containers).toHaveAttribute('href', '/environments/a/containers')
    expect(screen.getByRole('link', { name: /Images/ })).toHaveTextContent('12')
    expect(screen.getByRole('link', { name: /Volumes/ })).toHaveTextContent('4')
    for (const name of ['Deployments', 'Networks']) {
      expect(screen.getByRole('link', { name: new RegExp(name) })).toHaveAttribute(
        'href',
        `/environments/a/${name.toLowerCase()}`,
      )
    }
  })

  it('shows the error message when the environment is DOWN', async () => {
    apiFetch.mockResolvedValue([up, down])
    renderAt('b')
    expect(await screen.findByRole('alert')).toHaveTextContent('The connection timed out.')
    expect(screen.getByText('Down')).toBeInTheDocument()
  })

  it('renders a not-found state for an unknown id', async () => {
    apiFetch.mockResolvedValue([up])
    renderAt('zzz')
    expect(await screen.findByRole('heading', { name: 'Environment not found' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /environments/i })).toHaveAttribute('href', '/')
  })

  it('shows an error when loading fails', async () => {
    apiFetch.mockRejectedValue(new Error('boom'))
    renderAt('a')
    expect(await screen.findByRole('alert')).toHaveTextContent('Could not load the environment.')
  })
})
