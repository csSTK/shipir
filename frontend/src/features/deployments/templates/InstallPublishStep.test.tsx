import { QueryClient, QueryClientProvider, focusManager } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../../shared/api/client.ts'
import { InstallTemplateDialog } from './InstallTemplateDialog.tsx'
import type { AppTemplate } from './api.ts'

const apiFetch = vi.fn()
vi.mock('../../../shared/api/client.ts', async (importActual) => ({
  ...(await importActual<typeof import('../../../shared/api/client.ts')>()),
  apiFetch: (...args: unknown[]) => apiFetch(...args),
}))
vi.mock('../../domains/index.ts', async () => ({
  useManagedProxy: (await import('../../domains/api.ts')).useManagedProxy,
  PublishDialog: (p: { containerName?: string; port?: number; onClose: () => void; onDone?: () => void }) => (
    <div role="dialog" aria-label="Publish stub" data-container={p.containerName} data-port={p.port}>
      <button onClick={p.onDone}>stub done</button>
      <button onClick={p.onClose}>stub close</button>
    </div>
  ),
}))

afterEach(() => {
  vi.useRealTimers()
  cleanup()
  apiFetch.mockReset()
})

const wordpress: AppTemplate = {
  id: 'wordpress',
  name: 'WordPress',
  description: 'Blog engine',
  env: [],
  spec: { services: [{ name: 'wp', image: 'wordpress:6', ports: [{ host: 8080, container: 80 }], environment: [] }], networks: [], volumes: [] } as AppTemplate['spec'],
}

const readyProxy = { instance: { type: 'TRAEFIK', deploymentId: 'p1', deploymentStatus: 'RUNNING' }, setupBlockedReason: null }

function serve({
  role = 'OPERATOR',
  proxy = readyProxy as unknown,
  candidates = [{ containerName: 'wordpress-wp-1' }] as unknown,
  proxyGate,
  meError,
}: { role?: string; proxy?: unknown; candidates?: unknown; proxyGate?: Promise<void>; meError?: boolean } = {}) {
  apiFetch.mockImplementation((path: string, init?: { method?: string }) => {
    if (path === '/auth/me' && meError) return Promise.reject(new ApiError(500, 'x', 'X'))
    if (path === '/auth/me') return Promise.resolve({ username: 'u', role, setupRequired: false })
    if (path === '/hosts/h1/proxy/managed') {
      if (proxy instanceof Error) return Promise.reject(proxy)
      return proxyGate ? proxyGate.then(() => proxy) : Promise.resolve(proxy)
    }
    if (path === '/hosts/h1/proxy/routes/candidates') return Promise.resolve(candidates)
    if (path.startsWith('/hosts/h1/ports/free')) return Promise.resolve({ used: [], suggestions: [{ preferred: 8080, free: 8080 }] })
    if (path === '/hosts/h1/managed-deployments' && !init?.method) return Promise.resolve([])
    if (path === '/compose/generate') return Promise.resolve({ yaml: 'services: {}\n' })
    if (path === '/hosts/h1/managed-deployments' && init?.method === 'POST') return Promise.resolve({ id: 's1' })
    if (path === '/hosts/h1/managed-deployments/s1/deploy') return Promise.resolve({ runId: 'r1', versionId: 'v1' })
    return Promise.reject(new ApiError(404, 'x', 'NOT_FOUND'))
  })
}

function Where() {
  return <p data-testid="where">{useLocation().pathname}</p>
}

async function install(template: AppTemplate = wordpress) {
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter>
        <InstallTemplateDialog hostId="h1" template={template} onClose={() => {}} onAdvanced={() => {}} />
        <Routes>
          <Route path="*" element={<Where />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
  await waitFor(() => expect(screen.getByLabelText('Name')).toHaveValue('wordpress'))
  fireEvent.click(screen.getByRole('button', { name: 'Install' }))
}

describe('publish step after install', () => {
  it('offers publishing with a ready proxy and prefills the main container and web port', async () => {
    serve()
    await install()
    const publish = await screen.findByRole('button', { name: 'Publish with domain' })
    expect(screen.getByTestId('where')).not.toHaveTextContent('/deployments/s1')
    await waitFor(() => expect(publish).toBeEnabled())
    fireEvent.click(publish)
    const stub = screen.getByRole('dialog', { name: 'Publish stub' })
    expect(stub).toHaveAttribute('data-container', 'wordpress-wp-1')
    expect(stub).toHaveAttribute('data-port', '80')
    fireEvent.click(screen.getByRole('button', { name: 'stub done' }))
    await waitFor(() => expect(screen.getByTestId('where')).toHaveTextContent('/environments/h1/deployments/s1'))
  })

  it('skipping goes straight to the deployment', async () => {
    serve()
    await install()
    fireEvent.click(await screen.findByRole('button', { name: 'Skip' }))
    await waitFor(() => expect(screen.getByTestId('where')).toHaveTextContent('/environments/h1/deployments/s1'))
  })

  it('waits until the container exists before publishing is possible', async () => {
    serve({ candidates: [] })
    await install()
    expect(await screen.findByRole('button', { name: 'Publish with domain' })).toBeDisabled()
  })

  it('shows a hint to the Domains page when no managed proxy exists', async () => {
    serve({ proxy: { instance: null, setupBlockedReason: null } })
    await install()
    expect(await screen.findByRole('link', { name: 'Set up Domains' })).toHaveAttribute('href', '/environments/h1/domains')
    expect(screen.queryByRole('button', { name: 'Publish with domain' })).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Open deployment' }))
    await waitFor(() => expect(screen.getByTestId('where')).toHaveTextContent('/environments/h1/deployments/s1'))
  })

  it('treats a stopped proxy as not ready: hint instead of the publish button', async () => {
    serve({ proxy: { instance: { ...readyProxy.instance, deploymentStatus: 'STOPPED' }, setupBlockedReason: null } })
    await install()
    expect(await screen.findByRole('link', { name: 'Open Domains' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Publish with domain' })).toBeNull()
  })

  it('never shows the step to a user without operate rights', async () => {
    serve({ role: 'VIEWER' })
    await install()
    await waitFor(() => expect(screen.getByTestId('where')).toHaveTextContent('/environments/h1/deployments/s1'))
    expect(screen.queryByRole('button', { name: 'Publish with domain' })).toBeNull()
    expect(screen.queryByRole('link', { name: 'Set up Domains' })).toBeNull()
  })

  it('still offers the step when the proxy state resolves only after the install was submitted', async () => {
    let release!: () => void
    serve({ proxyGate: new Promise<void>((r) => (release = r)) })
    await install()
    await waitFor(() => expect(apiFetch).toHaveBeenCalledWith('/hosts/h1/managed-deployments/s1/deploy', expect.anything()))
    expect(screen.queryByRole('button', { name: 'Publish with domain' })).toBeNull()
    expect(screen.getByTestId('where')).not.toHaveTextContent('/deployments/s1')
    release()
    expect(await screen.findByRole('button', { name: 'Publish with domain' })).toBeInTheDocument()
  })

  it('shows the step to an ADMIN too', async () => {
    serve({ role: 'ADMIN' })
    await install()
    expect(await screen.findByRole('button', { name: 'Publish with domain' })).toBeInTheDocument()
  })

  it('goes straight to the deployment for an app without a published port', async () => {
    serve()
    await install({ ...wordpress, spec: { services: [{ name: 'worker', image: 'x', ports: [], environment: [] }], networks: [], volumes: [] } as AppTemplate['spec'] })
    await waitFor(() => expect(screen.getByTestId('where')).toHaveTextContent('/environments/h1/deployments/s1'))
    expect(screen.queryByRole('button', { name: 'Publish with domain' })).toBeNull()
  })

  it('falls back to the deployment when the proxy state cannot be read', async () => {
    serve({ proxy: new ApiError(500, 'boom', 'X') })
    await install()
    await waitFor(() => expect(screen.getByTestId('where')).toHaveTextContent('/environments/h1/deployments/s1'))
    expect(screen.queryByRole('link', { name: 'Set up Domains' })).toBeNull()
  })

  it('says the proxy is not running yet when it exists but is not running', async () => {
    serve({ proxy: { instance: { ...readyProxy.instance, deploymentStatus: 'STOPPED' }, setupBlockedReason: null } })
    await install()
    expect(await screen.findByText(/not running yet/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Open Domains' })).toHaveAttribute('href', '/environments/h1/domains')
  })

  it('keeps Publish disabled when no container matches the service', async () => {
    serve({ candidates: [{ containerName: 'wordpress-db-1' }] })
    await install()
    expect(await screen.findByRole('button', { name: 'Publish with domain' })).toBeDisabled()
  })

  it('stops polling once the container is found', async () => {
    serve()
    await install()
    await waitFor(() => expect(screen.getByRole('button', { name: 'Publish with domain' })).toBeEnabled())
    const n = apiFetch.mock.calls.filter((c) => c[0] === '/hosts/h1/proxy/routes/candidates').length
    await new Promise((r) => setTimeout(r, 2500))
    expect(apiFetch.mock.calls.filter((c) => c[0] === '/hosts/h1/proxy/routes/candidates')).toHaveLength(n)
  })

  it('gives up polling after a minute and points to the Domains page, Skip still works', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    serve({ candidates: [] })
    await install()
    await screen.findByRole('button', { name: 'Publish with domain' })
    await vi.advanceTimersByTimeAsync(61_000)
    expect(await screen.findByText(/taking longer than expected/)).toBeInTheDocument()
    const n = apiFetch.mock.calls.filter((c) => c[0] === '/hosts/h1/proxy/routes/candidates').length
    await vi.advanceTimersByTimeAsync(10_000)
    expect(apiFetch.mock.calls.filter((c) => c[0] === '/hosts/h1/proxy/routes/candidates')).toHaveLength(n)
    fireEvent.click(screen.getByRole('button', { name: 'Skip' }))
    await waitFor(() => expect(screen.getByTestId('where')).toHaveTextContent('/environments/h1/deployments/s1'))
  })

  it('stops polling when the step is unmounted', async () => {
    serve({ candidates: [] })
    await install()
    await screen.findByRole('button', { name: 'Publish with domain' })
    cleanup()
    await new Promise((r) => setTimeout(r, 50))
    const n = apiFetch.mock.calls.filter((c) => c[0] === '/hosts/h1/proxy/routes/candidates').length
    await new Promise((r) => setTimeout(r, 2500))
    expect(apiFetch.mock.calls.filter((c) => c[0] === '/hosts/h1/proxy/routes/candidates')).toHaveLength(n)
  })

  /** Mimics returning to the tab: React Query refetches stale queries on visibilitychange. */
  const refocus = () => {
    focusManager.setFocused(false)
    focusManager.setFocused(true)
  }
  const answerProxy = (body: unknown) => {
    const impl = apiFetch.getMockImplementation()!
    apiFetch.mockImplementation((path: string, init?: unknown) => {
      if (path === '/hosts/h1/proxy/managed') return body instanceof Error ? Promise.reject(body) : Promise.resolve(body)
      return impl(path, init)
    })
  }

  it('keeps the step and an open publish dialog when a later proxy refetch fails', async () => {
    serve()
    await install()
    const publish = await screen.findByRole('button', { name: 'Publish with domain' })
    await waitFor(() => expect(publish).toBeEnabled())
    fireEvent.click(publish)
    expect(screen.getByRole('dialog', { name: 'Publish stub' })).toBeInTheDocument()
    const before = apiFetch.mock.calls.filter((c) => c[0] === '/hosts/h1/proxy/managed').length
    answerProxy(new ApiError(500, 'boom', 'X'))
    refocus()
    await waitFor(() => expect(apiFetch.mock.calls.filter((c) => c[0] === '/hosts/h1/proxy/managed').length).toBeGreaterThan(before))
    await new Promise((r) => setTimeout(r, 50))
    expect(screen.getByRole('dialog', { name: 'Publish stub' })).toBeInTheDocument()
    expect(screen.getByTestId('where')).not.toHaveTextContent('/deployments/s1')
  })

  it('does not change the step when the proxy later stops being ready', async () => {
    serve()
    await install()
    await screen.findByRole('button', { name: 'Publish with domain' })
    const before = apiFetch.mock.calls.filter((c) => c[0] === '/hosts/h1/proxy/managed').length
    answerProxy({ instance: { ...readyProxy.instance, deploymentStatus: 'STOPPED' }, setupBlockedReason: null })
    refocus()
    await waitFor(() => expect(apiFetch.mock.calls.filter((c) => c[0] === '/hosts/h1/proxy/managed').length).toBeGreaterThan(before))
    await new Promise((r) => setTimeout(r, 50))
    expect(screen.getByRole('button', { name: 'Publish with domain' })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Open Domains' })).toBeNull()
  })

  it('goes straight to the deployment when the current user cannot be read', async () => {
    serve({ meError: true })
    await install()
    await waitFor(() => expect(screen.getByTestId('where')).toHaveTextContent('/environments/h1/deployments/s1'))
    expect(screen.queryByRole('button', { name: 'Publish with domain' })).toBeNull()
  })

  it('decides on fresh proxy data fetched after the deploy, not on a stale cached result', async () => {
    serve({ proxy: { instance: null, setupBlockedReason: null } })
    await install()
    await screen.findByRole('link', { name: 'Set up Domains' })
    expect(apiFetch.mock.calls.filter((c) => c[0] === '/hosts/h1/proxy/managed').length).toBeGreaterThanOrEqual(2)
  })
})
