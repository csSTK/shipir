import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../shared/api/client.ts'
import { DeploymentsPage } from './DeploymentsPage.tsx'

const apiFetch = vi.fn()
vi.mock('../../shared/api/client.ts', async (importActual) => ({
  ...(await importActual<typeof import('../../shared/api/client.ts')>()),
  apiFetch: (...args: unknown[]) => apiFetch(...args),
}))

afterEach(() => {
  cleanup()
  apiFetch.mockReset()
})

const deployments = [
  { name: 'shop', containerCount: 3, runningCount: 3, status: 'RUNNING', workingDir: '/srv/shop' },
  { name: 'blog', containerCount: 2, runningCount: 1, status: 'PARTIAL', workingDir: null },
  { name: 'old', containerCount: 1, runningCount: 0, status: 'STOPPED', workingDir: '/srv/old' },
]

const me = (role: string) => ({ username: 'u', role, setupRequired: false })

/** Routes mocked API calls by path; unknown paths reject like a 404. */
function serve(live: unknown, opts: { role?: string; managed?: unknown[] } = {}) {
  apiFetch.mockImplementation((path: string) => {
    if (path === '/auth/me') return Promise.resolve(me(opts.role ?? 'VIEWER'))
    if (path === '/hosts/h1/deployments') return live instanceof Error ? Promise.reject(live) : Promise.resolve(live)
    if (path === '/hosts/h1/managed-deployments') return Promise.resolve(opts.managed ?? [])
    return Promise.reject(new ApiError(404, 'x', 'NOT_FOUND'))
  })
}

function Where() {
  const l = useLocation()
  return <p data-testid="where">{l.pathname + l.search}</p>
}

function renderPage() {
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter initialEntries={['/environments/h1/deployments']}>
        <Routes>
          <Route path="/environments/:id/deployments" element={<DeploymentsPage />} />
          <Route path="/environments/:id/containers" element={<Where />} />
          <Route path="/environments/:id/deployments/:deploymentId" element={<Where />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

function names() {
  return screen
    .getAllByRole('row', { hidden: true })
    .slice(1)
    .map((r) => within(r).getAllByRole('cell', { hidden: true })[0].textContent)
}

describe('DeploymentsPage', () => {
  it('lists deployments with status, running/total and working directory', async () => {
    serve(deployments)
    renderPage()
    expect(screen.getByRole('status', { name: 'Loading deployments' })).toBeInTheDocument()
    await screen.findByText('/srv/shop')
    expect(apiFetch).toHaveBeenCalledWith('/hosts/h1/deployments')
    expect(names()).toEqual(['blog', 'old', 'shop'])
    const row = screen.getByRole('row', { name: /blog/ })
    expect(within(row).getByText('Partial')).toBeInTheDocument()
    expect(within(row).getByText('1 / 2')).toBeInTheDocument()
  })

  it('searches and sorts by name', async () => {
    serve(deployments)
    renderPage()
    await screen.findByText('/srv/shop')
    fireEvent.click(screen.getByRole('button', { name: 'Name' }))
    expect(names()).toEqual(['shop', 'old', 'blog'])
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'BL' } })
    expect(names()).toEqual(['blog'])
  })

  it('opens the containers page filtered by the clicked deployment', async () => {
    serve(deployments)
    renderPage()
    await screen.findByText('/srv/shop')
    fireEvent.click(screen.getByRole('link', { name: 'shop' }))
    expect(screen.getByTestId('where')).toHaveTextContent('/environments/h1/containers?deployment=shop')
  })

  it('shows a message for UNREACHABLE', async () => {
    serve(new ApiError(502, 'x', 'UNREACHABLE'))
    renderPage()
    expect(await screen.findByRole('alert')).toHaveTextContent('Could not reach the Docker host.')
  })

  it('shows the empty state', async () => {
    serve([])
    renderPage()
    expect(await screen.findByText('No deployments in this environment.')).toBeInTheDocument()
  })

  it('shows managed deployments, including ones never deployed, and links them to the detail page', async () => {
    serve(deployments, {
      managed: [
        { id: 'm1', name: 'shop', status: 'RUNNING', source: 'FILE', lastDeployedAt: null, createdAt: '2026-10-01T10:00:00Z' },
        { id: 'm2', name: 'draft', status: 'STOPPED', source: 'FILE', lastDeployedAt: null, createdAt: '2026-10-01T10:00:00Z' },
      ],
    })
    renderPage()
    await screen.findByText('/srv/shop')
    expect(names()).toEqual(['blog', 'draft', 'old', 'shop'])
    expect(within(screen.getByRole('row', { name: /draft/ })).getByText('Not deployed')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('link', { name: 'draft' }))
    expect(screen.getByTestId('where')).toHaveTextContent('/environments/h1/deployments/m2')
  })

  it('hides Add deployment and row actions from viewers', async () => {
    serve(deployments, { managed: [{ id: 'm1', name: 'shop', status: 'RUNNING', source: 'FILE', lastDeployedAt: null, createdAt: 'x' }] })
    renderPage()
    await screen.findByText('/srv/shop')
    expect(screen.queryByRole('button', { name: 'Add deployment' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Deploy shop' })).not.toBeInTheDocument()
  })

  it('lets operators add a deployment and deploy a managed one', async () => {
    serve(deployments, { role: 'OPERATOR', managed: [{ id: 'm1', name: 'shop', status: 'RUNNING', source: 'FILE', lastDeployedAt: null, createdAt: 'x' }] })
    renderPage()
    await screen.findByText('/srv/shop')
    expect(screen.getByRole('button', { name: 'Add deployment' })).toBeInTheDocument()
    apiFetch.mockImplementation((path: string, init?: { method?: string }) =>
      path === '/hosts/h1/managed-deployments/m1/deploy' && init?.method === 'POST'
        ? Promise.resolve({ runId: 'd-1', versionId: 'v1' })
        : Promise.resolve([]),
    )
    fireEvent.click(screen.getByRole('button', { name: 'Deploy shop' }))
    expect(await screen.findByRole('status')).toHaveTextContent('d-1')
  })

  it('creates a deployment and opens its detail page', async () => {
    serve(deployments, { role: 'ADMIN' })
    renderPage()
    await screen.findByText('/srv/shop')
    fireEvent.click(screen.getByRole('button', { name: 'Add deployment' }))
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'web' } })
    fireEvent.change(await screen.findByLabelText('Compose file'), { target: { value: 'services: {}' } })
    fireEvent.click(screen.getByRole('button', { name: 'Add variable' }))
    fireEvent.change(screen.getByLabelText('Variable 1 name'), { target: { value: 'A' } })
    fireEvent.change(screen.getByLabelText('Variable 1 value'), { target: { value: 'b' } })
    apiFetch.mockImplementation((_path: string, init?: { method?: string }) =>
      init?.method === 'POST' ? Promise.resolve({ id: 'new1' }) : Promise.resolve([]),
    )
    fireEvent.click(screen.getByRole('button', { name: 'Create deployment' }))
    await vi.waitFor(() => expect(screen.getByTestId('where')).toHaveTextContent('/environments/h1/deployments/new1'))
    expect(apiFetch).toHaveBeenCalledWith('/hosts/h1/managed-deployments', {
      method: 'POST',
      body: { name: 'web', yaml: 'services: {}', env: [{ key: 'A', value: 'b' }] },
    })
  })

  it('shows server errors in the add dialog', async () => {
    serve(deployments, { role: 'ADMIN' })
    renderPage()
    await screen.findByText('/srv/shop')
    fireEvent.click(screen.getByRole('button', { name: 'Add deployment' }))
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'web' } })
    fireEvent.change(await screen.findByLabelText('Compose file'), { target: { value: 'x' } })
    apiFetch.mockRejectedValue(new ApiError(409, 'taken', 'DEPLOYMENT_NAME_TAKEN'))
    fireEvent.click(screen.getByRole('button', { name: 'Create deployment' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('already exists')
  })

  it('rejects an invalid name before calling the server', async () => {
    serve(deployments, { role: 'ADMIN' })
    renderPage()
    await screen.findByText('/srv/shop')
    fireEvent.click(screen.getByRole('button', { name: 'Add deployment' }))
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Bad Name' } })
    fireEvent.change(await screen.findByLabelText('Compose file'), { target: { value: 'x' } })
    fireEvent.click(screen.getByRole('button', { name: 'Create deployment' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('lowercase')
  })

  it('shows INVALID_COMPOSE details at the editor in the add dialog', async () => {
    serve(deployments, { role: 'ADMIN' })
    renderPage()
    await screen.findByText('/srv/shop')
    fireEvent.click(screen.getByRole('button', { name: 'Add deployment' }))
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'web' } })
    fireEvent.change(await screen.findByLabelText('Compose file'), { target: { value: 'x' } })
    apiFetch.mockRejectedValue(new ApiError(400, 'bad', 'INVALID_COMPOSE', undefined, ['line 1: not a mapping']))
    fireEvent.click(screen.getByRole('button', { name: 'Create deployment' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('line 1: not a mapping')
  })

  describe('form wizard', () => {
    const generated = 'services:\n  web:\n    image: nginx\n'

    async function openWizard() {
      serve(deployments, { role: 'ADMIN' })
      renderPage()
      await screen.findByText('/srv/shop')
      fireEvent.click(screen.getByRole('button', { name: 'Add deployment' }))
      fireEvent.click(screen.getByRole('radio', { name: 'Form wizard' }))
    }

    it('generates YAML, previews it and continues in the editor with referenced variables', async () => {
      await openWizard()
      fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'web' } })
      fireEvent.change(screen.getByLabelText('Service 1 name'), { target: { value: 'web' } })
      fireEvent.change(screen.getByLabelText('Service 1 image'), { target: { value: 'nginx' } })
      fireEvent.click(screen.getByRole('button', { name: 'Add env var to service 1' }))
      fireEvent.change(screen.getByLabelText('Service 1 env 1 name'), { target: { value: 'TOKEN' } })
      fireEvent.click(screen.getByLabelText('From deployment variable'))
      fireEvent.change(screen.getByLabelText('Service 1 env 1 reference'), { target: { value: 'API_TOKEN' } })
      apiFetch.mockResolvedValue({ yaml: generated })
      fireEvent.click(screen.getByRole('button', { name: 'Preview YAML' }))
      fireEvent.click(await screen.findByRole('button', { name: 'Continue in editor' }))
      expect(apiFetch).toHaveBeenCalledWith('/compose/generate', {
        method: 'POST',
        body: {
          spec: expect.objectContaining({
            services: [expect.objectContaining({ name: 'web', image: 'nginx', environment: [{ key: 'TOKEN', value: null, ref: 'API_TOKEN' }] })],
          }),
          hostId: 'h1',
        },
      })
      expect(screen.getByRole('radio', { name: 'YAML' })).toBeChecked()
      const editors = await screen.findAllByLabelText('Compose file')
      expect(editors.some((e) => (e as HTMLTextAreaElement).value === generated)).toBe(true)
      expect(screen.getByLabelText('Variable 1 name')).toHaveValue('API_TOKEN')
    })

    it('blocks the preview while the form is invalid and does not call the server', async () => {
      await openWizard()
      apiFetch.mockClear()
      fireEvent.click(screen.getByRole('button', { name: 'Preview YAML' }))
      expect(await screen.findByText('Fix the highlighted fields first.')).toBeInTheDocument()
      expect(apiFetch).not.toHaveBeenCalled()
    })

    it('lists all server violations', async () => {
      await openWizard()
      fireEvent.change(screen.getByLabelText('Service 1 name'), { target: { value: 'web' } })
      fireEvent.change(screen.getByLabelText('Service 1 image'), { target: { value: 'nginx' } })
      apiFetch.mockRejectedValue(new ApiError(400, 'bad', 'INVALID_COMPOSE_SPEC', undefined, ['services[0]: port clash', 'services[1]: unknown network']))
      fireEvent.click(screen.getByRole('button', { name: 'Preview YAML' }))
      const alert = await screen.findByText('services[0]: port clash')
      expect(alert.closest('[role="alert"]')).toHaveTextContent('services[1]: unknown network')
    })
  })

  describe('removing unmanaged deployments', () => {
    const managedShop = { id: 'm1', name: 'shop', status: 'RUNNING', source: 'FILE', lastDeployedAt: null, createdAt: 'x' }

    /** Serves the list and answers DELETE of a project with `remove`. */
    function serveWithDelete(role: string, remove: () => Promise<unknown>) {
      apiFetch.mockImplementation((path: string, init?: { method?: string }) => {
        if (path === '/auth/me') return Promise.resolve(me(role))
        if (path === '/hosts/h1/deployments') return Promise.resolve(deployments)
        if (path === '/hosts/h1/managed-deployments') return Promise.resolve([managedShop])
        if (path.startsWith('/hosts/h1/deployments/') && init?.method === 'DELETE') return remove()
        return Promise.reject(new ApiError(404, 'x', 'NOT_FOUND'))
      })
    }

    async function openRemove(name: string) {
      renderPage()
      await screen.findByText('/srv/shop')
      fireEvent.click(screen.getByRole('button', { name: `Remove ${name}` }))
      return screen.findByRole('dialog')
    }

    it('hides Remove from viewers', async () => {
      serveWithDelete('VIEWER', () => Promise.resolve(undefined))
      renderPage()
      await screen.findByText('/srv/shop')
      expect(screen.queryByRole('button', { name: 'Remove blog' })).not.toBeInTheDocument()
      expect(screen.queryByRole('button', { name: 'Remove shop' })).not.toBeInTheDocument()
    })

    it('does not offer Remove for managed deployments', async () => {
      serveWithDelete('OPERATOR', () => Promise.resolve(undefined))
      renderPage()
      await screen.findByText('/srv/shop')
      expect(screen.queryByRole('button', { name: 'Remove shop' })).not.toBeInTheDocument()
      expect(screen.getByRole('button', { name: 'Remove blog' })).toBeInTheDocument()
    })

    it('asks for confirmation and deletes keeping volumes by default', async () => {
      const remove = vi.fn(() => Promise.resolve(undefined))
      serveWithDelete('OPERATOR', remove)
      const dialog = await openRemove('blog')
      expect(within(dialog).getByLabelText('Also remove volumes')).not.toBeChecked()
      expect(remove).not.toHaveBeenCalled()
      fireEvent.click(within(dialog).getByRole('button', { name: 'Remove' }))
      await vi.waitFor(() => expect(remove).toHaveBeenCalled())
      expect(apiFetch).toHaveBeenCalledWith('/hosts/h1/deployments/blog?removeVolumes=false', { method: 'DELETE' })
      expect(await screen.findByRole('status')).toHaveTextContent('blog removed.')
    })

    it('sends removeVolumes=true when the option is checked', async () => {
      const remove = vi.fn(() => Promise.resolve(undefined))
      serveWithDelete('ADMIN', remove)
      const dialog = await openRemove('old')
      fireEvent.click(within(dialog).getByLabelText('Also remove volumes'))
      fireEvent.click(within(dialog).getByRole('button', { name: 'Remove' }))
      await vi.waitFor(() => expect(remove).toHaveBeenCalled())
      expect(apiFetch).toHaveBeenCalledWith('/hosts/h1/deployments/old?removeVolumes=true', { method: 'DELETE' })
    })

    it('cancel closes the dialog without deleting', async () => {
      const remove = vi.fn(() => Promise.resolve(undefined))
      serveWithDelete('OPERATOR', remove)
      const dialog = await openRemove('blog')
      fireEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }))
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
      expect(remove).not.toHaveBeenCalled()
    })

    it('shows COMPOSE_DOWN_FAILED inline and keeps the deployment listed', async () => {
      serveWithDelete('OPERATOR', () => Promise.reject(new ApiError(502, 'x', 'COMPOSE_DOWN_FAILED', undefined, ['boom'])))
      const dialog = await openRemove('blog')
      fireEvent.click(within(dialog).getByRole('button', { name: 'Remove' }))
      expect(await within(dialog).findByRole('alert')).toHaveTextContent('Docker could not stop the deployment')
      expect(screen.getByRole('dialog')).toBeInTheDocument()
      expect(names()).toContain('blog')
    })
  })

  it('shows the source of managed deployments and opens details from the overflow menu', async () => {
    serve(
      [{ name: 'shop', containerCount: 1, runningCount: 1, status: 'RUNNING', workingDir: null, managed: true, managedDeploymentId: 'm1' }],
      {
        role: 'OPERATOR',
        managed: [
          { id: 'm1', name: 'shop', status: 'RUNNING', source: 'GIT', lastDeployedAt: null, createdAt: '2026-10-01T10:00:00Z' },
          { id: 'm2', name: 'draft', status: 'STOPPED', source: 'FILE', lastDeployedAt: null, createdAt: '2026-10-01T10:00:00Z' },
        ],
      },
    )
    renderPage()
    await screen.findByRole('link', { name: 'shop' })
    expect(within(screen.getByRole('row', { name: /shop/ })).getByText('Git')).toBeInTheDocument()
    expect(within(screen.getByRole('row', { name: /draft/ })).getByText('File')).toBeInTheDocument()
    fireEvent.pointerDown(screen.getByRole('button', { name: 'More actions for draft' }), { button: 0, ctrlKey: false })
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Open details' }))
    expect(await screen.findByTestId('where')).toHaveTextContent('/environments/h1/deployments/m2')
  })

  it('shows an update badge linking managed deployments to their detail page', async () => {
    serve(
      [
        { name: 'shop', containerCount: 1, runningCount: 1, status: 'RUNNING', workingDir: null, managed: true, managedDeploymentId: 'm1', updateAvailable: true },
        { name: 'blog', containerCount: 1, runningCount: 1, status: 'RUNNING', workingDir: null, updateAvailable: true },
        { name: 'old', containerCount: 1, runningCount: 1, status: 'RUNNING', workingDir: null, updateAvailable: false },
      ],
      { managed: [{ id: 'm1', name: 'shop', status: 'RUNNING', source: 'FILE', lastDeployedAt: null, createdAt: '2026-10-01T10:00:00Z' }] },
    )
    renderPage()
    await screen.findByText('shop')
    expect(screen.getAllByText('Update available')).toHaveLength(2)
    fireEvent.click(screen.getByRole('link', { name: 'Pull & update shop' }))
    expect(await screen.findByTestId('where')).toHaveTextContent('/environments/h1/deployments/m1')
    expect(screen.queryByRole('link', { name: 'Pull & update blog' })).not.toBeInTheDocument()
  })
})
