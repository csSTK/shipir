import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../shared/api/client.ts'
import { ENV_RESOURCES } from '../hosts/resources.ts'
import { DomainsPage } from './DomainsPage.tsx'

const apiFetch = vi.fn()
vi.mock('../../shared/api/client.ts', async (importActual) => ({
  ...(await importActual<typeof import('../../shared/api/client.ts')>()),
  apiFetch: (...args: unknown[]) => apiFetch(...args),
}))
vi.mock('../deployments/deploy-output/DeployOutput.tsx', () => ({
  DeployOutput: ({ runId, onFinished }: { runId: string; onFinished?: (r: 'DONE' | 'FAILED') => void }) => (
    <div>
      <p>output of run {runId}</p>
      <button onClick={() => onFinished?.('DONE')}>finish run</button>
      <button onClick={() => onFinished?.('FAILED')}>fail run</button>
    </div>
  ),
}))

afterEach(() => {
  cleanup()
  apiFetch.mockReset()
})

const instance = {
  type: 'TRAEFIK',
  acmeEmail: 'ops@example.com',
  defaultCertMode: 'ACME_HTTP',
  dnsProvider: null,
  dnsCredentialsHint: null,
  deploymentId: 'd1',
  deploymentStatus: 'RUNNING',
}

function mockApi(role: string, managed: unknown, post?: unknown) {
  apiFetch.mockImplementation((path: string, options?: { method?: string }) => {
    if (path === '/auth/me') return Promise.resolve({ username: 'u', role, setupRequired: false })
    if (path === '/hosts/h1/proxy/managed' && options?.method === 'POST') {
      return post instanceof Error ? Promise.reject(post) : Promise.resolve(post)
    }
    if (path === '/hosts/h1/proxy/managed') return Promise.resolve(managed)
    return Promise.reject(new Error(`unexpected ${path}`))
  })
}

function renderPage() {
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter initialEntries={['/environments/h1/domains']}>
        <Routes>
          <Route path="/environments/:id/domains" element={<DomainsPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('DomainsPage', () => {
  it('registers Domains as an environment resource', () => {
    expect(ENV_RESOURCES.map((r) => r.slug)).toContain('domains')
  })

  it('explains an existing proxy in plain language and offers no setup form', async () => {
    mockApi('ADMIN', { instance: null, setupBlockedReason: 'EXISTING_PROXY' })
    renderPage()
    expect(await screen.findByText(/already runs a reverse proxy/i)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Open containers' })).toHaveAttribute('href', '/environments/h1/containers')
    expect(screen.queryByRole('button', { name: 'Set up proxy' })).not.toBeInTheDocument()
  })

  it('explains busy ports when port 80 or 443 is taken', async () => {
    mockApi('ADMIN', { instance: null, setupBlockedReason: 'PORTS_IN_USE' })
    renderPage()
    expect(await screen.findByText(/port 80 or 443/i)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Set up proxy' })).not.toBeInTheDocument()
  })

  it('shows the setup form to an admin with Traefik preselected', async () => {
    mockApi('ADMIN', { instance: null, setupBlockedReason: null })
    renderPage()
    expect(await screen.findByRole('radio', { name: /Traefik/ })).toBeChecked()
    expect(screen.getByRole('radio', { name: /Caddy/ })).not.toBeChecked()
    expect(screen.getByRole('radio', { name: /Automatic/ })).toBeChecked()
  })

  it('sends Traefik, e-mail and automatic certificates on submit and then shows the live output', async () => {
    mockApi('ADMIN', { instance: null, setupBlockedReason: null }, { instance, runId: 'run-7' })
    renderPage()
    fireEvent.change(await screen.findByLabelText('E-mail for Let’s Encrypt'), { target: { value: 'ops@example.com' } })
    fireEvent.click(screen.getByRole('button', { name: 'Set up proxy' }))
    expect(await screen.findByText('output of run run-7')).toBeInTheDocument()
    expect(apiFetch).toHaveBeenCalledWith('/hosts/h1/proxy/managed', {
      method: 'POST',
      body: { type: 'TRAEFIK', acmeEmail: 'ops@example.com', defaultCertMode: 'ACME_HTTP' },
    })
  })

  it('sends Caddy when chosen and accepts a missing e-mail', async () => {
    mockApi('ADMIN', { instance: null, setupBlockedReason: null }, { instance: { ...instance, type: 'CADDY' }, runId: 'r' })
    renderPage()
    fireEvent.click(await screen.findByRole('radio', { name: /Caddy/ }))
    fireEvent.click(screen.getByRole('button', { name: 'Set up proxy' }))
    await screen.findByText('output of run r')
    expect(apiFetch).toHaveBeenCalledWith('/hosts/h1/proxy/managed', {
      method: 'POST',
      body: { type: 'CADDY', defaultCertMode: 'ACME_HTTP' },
    })
  })

  it('requires an e-mail for Traefik before calling the server', async () => {
    mockApi('ADMIN', { instance: null, setupBlockedReason: null })
    renderPage()
    fireEvent.click(await screen.findByRole('button', { name: 'Set up proxy' }))
    expect(await screen.findByText(/Enter an e-mail address/)).toBeInTheDocument()
    expect(apiFetch).not.toHaveBeenCalledWith('/hosts/h1/proxy/managed', expect.objectContaining({ method: 'POST' }))
  })

  it('tells the user certificates are uploaded in the next step when choosing their own', async () => {
    mockApi('ADMIN', { instance: null, setupBlockedReason: null })
    renderPage()
    fireEvent.click(await screen.findByRole('radio', { name: /own certificate/ }))
    expect(screen.getByText(/starts with automatic Let.s Encrypt certificates/i)).toBeInTheDocument()
  })

  it('shows a 409 PROXY_SETUP_BLOCKED error in plain language', async () => {
    mockApi(
      'ADMIN',
      { instance: null, setupBlockedReason: null },
      new ApiError(409, 'x', 'PROXY_SETUP_BLOCKED'),
    )
    renderPage()
    fireEvent.change(await screen.findByLabelText('E-mail for Let’s Encrypt'), { target: { value: 'ops@example.com' } })
    fireEvent.click(screen.getByRole('button', { name: 'Set up proxy' }))
    expect(await screen.findByRole('alert')).toHaveTextContent(/another proxy|port 80 or 443/i)
  })

  it('shows PROXY_EXISTS in plain language', async () => {
    mockApi('ADMIN', { instance: null, setupBlockedReason: null }, new ApiError(409, 'x', 'PROXY_EXISTS'))
    renderPage()
    fireEvent.change(await screen.findByLabelText('E-mail for Let’s Encrypt'), { target: { value: 'ops@example.com' } })
    fireEvent.click(screen.getByRole('button', { name: 'Set up proxy' }))
    expect(await screen.findByRole('alert')).toHaveTextContent(/already has a proxy/i)
  })

  it('switches to the state the server reported after a PROXY_EXISTS conflict', async () => {
    let conflict = false
    apiFetch.mockImplementation((path: string, options?: { method?: string }) => {
      if (path === '/auth/me') return Promise.resolve({ username: 'u', role: 'ADMIN', setupRequired: false })
      if (options?.method === 'POST') {
        conflict = true
        return Promise.reject(new ApiError(409, 'x', 'PROXY_EXISTS'))
      }
      return Promise.resolve(conflict ? { instance, setupBlockedReason: null } : { instance: null, setupBlockedReason: null })
    })
    renderPage()
    fireEvent.change(await screen.findByLabelText('E-mail for Let’s Encrypt'), { target: { value: 'ops@example.com' } })
    fireEvent.click(screen.getByRole('button', { name: 'Set up proxy' }))
    expect(await screen.findByText(/No domains published yet/)).toBeInTheDocument()
  })

  it('shows a failure notice and the error badge when the setup run failed', async () => {
    let done = false
    apiFetch.mockImplementation((path: string, options?: { method?: string }) => {
      if (path === '/auth/me') return Promise.resolve({ username: 'u', role: 'ADMIN', setupRequired: false })
      if (options?.method === 'POST') {
        done = true
        return Promise.resolve({ instance, runId: 'run-3' })
      }
      return Promise.resolve(done ? { instance: { ...instance, deploymentStatus: 'ERROR' }, setupBlockedReason: null } : { instance: null, setupBlockedReason: null })
    })
    renderPage()
    fireEvent.change(await screen.findByLabelText('E-mail for Let’s Encrypt'), { target: { value: 'ops@example.com' } })
    fireEvent.click(screen.getByRole('button', { name: 'Set up proxy' }))
    fireEvent.click(await screen.findByRole('button', { name: 'fail run' }))
    expect(await screen.findByRole('alert')).toHaveTextContent(/setup did not finish/i)
    expect(await screen.findByText('Error')).toBeInTheDocument()
  })

  it.each(['VIEWER', 'OPERATOR'])('shows %s a read-only explanation instead of the form', async (role) => {
    mockApi(role, { instance: null, setupBlockedReason: null })
    renderPage()
    expect(await screen.findByText(/Only administrators can set up/i)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Set up proxy' })).not.toBeInTheDocument()
    expect(screen.queryByRole('radio')).not.toBeInTheDocument()
  })

  it('shows the ready state with proxy type, status and an empty publish list', async () => {
    mockApi('VIEWER', { instance, setupBlockedReason: null })
    renderPage()
    expect(await screen.findByRole('heading', { name: 'Traefik' })).toBeInTheDocument()
    expect(screen.getByText('Running')).toBeInTheDocument()
    expect(screen.getByText(/No domains published yet/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Open proxy deployment' })).toHaveAttribute('href', '/environments/h1/deployments/d1')
  })

  it('refreshes to the ready state after the setup run finishes', async () => {
    let done = false
    apiFetch.mockImplementation((path: string, options?: { method?: string }) => {
      if (path === '/auth/me') return Promise.resolve({ username: 'u', role: 'ADMIN', setupRequired: false })
      if (options?.method === 'POST') {
        done = true
        return Promise.resolve({ instance, runId: 'run-9' })
      }
      return Promise.resolve(done ? { instance, setupBlockedReason: null } : { instance: null, setupBlockedReason: null })
    })
    renderPage()
    fireEvent.change(await screen.findByLabelText('E-mail for Let’s Encrypt'), { target: { value: 'ops@example.com' } })
    fireEvent.click(screen.getByRole('button', { name: 'Set up proxy' }))
    fireEvent.click(await screen.findByRole('button', { name: 'finish run' }))
    await waitFor(() => expect(screen.getByText(/No domains published yet/)).toBeInTheDocument())
  })

  it('shows a retryable error when the proxy state cannot be loaded', async () => {
    apiFetch.mockImplementation((path: string) =>
      path === '/auth/me'
        ? Promise.resolve({ username: 'u', role: 'ADMIN', setupRequired: false })
        : Promise.reject(new ApiError(500, 'boom')),
    )
    renderPage()
    expect(await screen.findByRole('alert')).toHaveTextContent(/Could not load/)
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument()
  })
})
