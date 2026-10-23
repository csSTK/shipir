import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../shared/api/client.ts'
import { StacksPage } from './StacksPage.tsx'

const apiFetch = vi.fn()
vi.mock('../../shared/api/client.ts', async (importActual) => ({
  ...(await importActual<typeof import('../../shared/api/client.ts')>()),
  apiFetch: (...args: unknown[]) => apiFetch(...args),
}))

afterEach(() => {
  cleanup()
  apiFetch.mockReset()
})

const stacks = [
  { name: 'shop', containerCount: 3, runningCount: 3, status: 'RUNNING', workingDir: '/srv/shop' },
  { name: 'blog', containerCount: 2, runningCount: 1, status: 'PARTIAL', workingDir: null },
  { name: 'old', containerCount: 1, runningCount: 0, status: 'STOPPED', workingDir: '/srv/old' },
]

const me = (role: string) => ({ username: 'u', role, setupRequired: false })

/** Routes mocked API calls by path; unknown paths reject like a 404. */
function serve(live: unknown, opts: { role?: string; managed?: unknown[] } = {}) {
  apiFetch.mockImplementation((path: string) => {
    if (path === '/auth/me') return Promise.resolve(me(opts.role ?? 'VIEWER'))
    if (path === '/hosts/h1/stacks') return live instanceof Error ? Promise.reject(live) : Promise.resolve(live)
    if (path === '/hosts/h1/managed-stacks') return Promise.resolve(opts.managed ?? [])
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
      <MemoryRouter initialEntries={['/environments/h1/stacks']}>
        <Routes>
          <Route path="/environments/:id/stacks" element={<StacksPage />} />
          <Route path="/environments/:id/containers" element={<Where />} />
          <Route path="/environments/:id/stacks/:stackId" element={<Where />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

function names() {
  return screen
    .getAllByRole('row')
    .slice(1)
    .map((r) => within(r).getAllByRole('cell')[0].textContent)
}

describe('StacksPage', () => {
  it('lists stacks with status, running/total and working directory', async () => {
    serve(stacks)
    renderPage()
    expect(screen.getByRole('status', { name: 'Loading stacks' })).toBeInTheDocument()
    await screen.findByText('/srv/shop')
    expect(apiFetch).toHaveBeenCalledWith('/hosts/h1/stacks')
    expect(names()).toEqual(['blog', 'old', 'shop'])
    const row = screen.getByRole('row', { name: /blog/ })
    expect(within(row).getByText('Partial')).toBeInTheDocument()
    expect(within(row).getByText('1 / 2')).toBeInTheDocument()
  })

  it('searches and sorts by name', async () => {
    serve(stacks)
    renderPage()
    await screen.findByText('/srv/shop')
    fireEvent.click(screen.getByRole('button', { name: 'Name' }))
    expect(names()).toEqual(['shop', 'old', 'blog'])
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'BL' } })
    expect(names()).toEqual(['blog'])
  })

  it('opens the containers page filtered by the clicked stack', async () => {
    serve(stacks)
    renderPage()
    await screen.findByText('/srv/shop')
    fireEvent.click(screen.getByRole('link', { name: 'shop' }))
    expect(screen.getByTestId('where')).toHaveTextContent('/environments/h1/containers?stack=shop')
  })

  it('shows a message for UNREACHABLE', async () => {
    serve(new ApiError(502, 'x', 'UNREACHABLE'))
    renderPage()
    expect(await screen.findByRole('alert')).toHaveTextContent('Could not reach the Docker host.')
  })

  it('shows the empty state', async () => {
    serve([])
    renderPage()
    expect(await screen.findByText('No stacks in this environment.')).toBeInTheDocument()
  })

  it('shows managed stacks, including ones never deployed, and links them to the detail page', async () => {
    serve(stacks, {
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
    expect(screen.getByTestId('where')).toHaveTextContent('/environments/h1/stacks/m2')
  })

  it('hides Add stack and row actions from viewers', async () => {
    serve(stacks, { managed: [{ id: 'm1', name: 'shop', status: 'RUNNING', source: 'FILE', lastDeployedAt: null, createdAt: 'x' }] })
    renderPage()
    await screen.findByText('/srv/shop')
    expect(screen.queryByRole('button', { name: 'Add stack' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Deploy shop' })).not.toBeInTheDocument()
  })

  it('lets operators add a stack and deploy a managed one', async () => {
    serve(stacks, { role: 'OPERATOR', managed: [{ id: 'm1', name: 'shop', status: 'RUNNING', source: 'FILE', lastDeployedAt: null, createdAt: 'x' }] })
    renderPage()
    await screen.findByText('/srv/shop')
    expect(screen.getByRole('button', { name: 'Add stack' })).toBeInTheDocument()
    apiFetch.mockImplementation((path: string, init?: { method?: string }) =>
      path === '/hosts/h1/managed-stacks/m1/deploy' && init?.method === 'POST'
        ? Promise.resolve({ deploymentId: 'd-1', versionId: 'v1' })
        : Promise.resolve([]),
    )
    fireEvent.click(screen.getByRole('button', { name: 'Deploy shop' }))
    expect(await screen.findByRole('status')).toHaveTextContent('d-1')
  })

  it('creates a stack and opens its detail page', async () => {
    serve(stacks, { role: 'ADMIN' })
    renderPage()
    await screen.findByText('/srv/shop')
    fireEvent.click(screen.getByRole('button', { name: 'Add stack' }))
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'web' } })
    fireEvent.change(await screen.findByLabelText('Compose file'), { target: { value: 'services: {}' } })
    fireEvent.click(screen.getByRole('button', { name: 'Add variable' }))
    fireEvent.change(screen.getByLabelText('Variable 1 name'), { target: { value: 'A' } })
    fireEvent.change(screen.getByLabelText('Variable 1 value'), { target: { value: 'b' } })
    apiFetch.mockImplementation((_path: string, init?: { method?: string }) =>
      init?.method === 'POST' ? Promise.resolve({ id: 'new1' }) : Promise.resolve([]),
    )
    fireEvent.click(screen.getByRole('button', { name: 'Create stack' }))
    await vi.waitFor(() => expect(screen.getByTestId('where')).toHaveTextContent('/environments/h1/stacks/new1'))
    expect(apiFetch).toHaveBeenCalledWith('/hosts/h1/managed-stacks', {
      method: 'POST',
      body: { name: 'web', yaml: 'services: {}', env: [{ key: 'A', value: 'b' }] },
    })
  })

  it('shows server errors in the add dialog', async () => {
    serve(stacks, { role: 'ADMIN' })
    renderPage()
    await screen.findByText('/srv/shop')
    fireEvent.click(screen.getByRole('button', { name: 'Add stack' }))
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'web' } })
    fireEvent.change(await screen.findByLabelText('Compose file'), { target: { value: 'x' } })
    apiFetch.mockRejectedValue(new ApiError(409, 'taken', 'STACK_NAME_TAKEN'))
    fireEvent.click(screen.getByRole('button', { name: 'Create stack' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('already exists')
  })

  it('rejects an invalid name before calling the server', async () => {
    serve(stacks, { role: 'ADMIN' })
    renderPage()
    await screen.findByText('/srv/shop')
    fireEvent.click(screen.getByRole('button', { name: 'Add stack' }))
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Bad Name' } })
    fireEvent.change(await screen.findByLabelText('Compose file'), { target: { value: 'x' } })
    fireEvent.click(screen.getByRole('button', { name: 'Create stack' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('lowercase')
  })

  it('shows INVALID_COMPOSE details at the editor in the add dialog', async () => {
    serve(stacks, { role: 'ADMIN' })
    renderPage()
    await screen.findByText('/srv/shop')
    fireEvent.click(screen.getByRole('button', { name: 'Add stack' }))
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'web' } })
    fireEvent.change(await screen.findByLabelText('Compose file'), { target: { value: 'x' } })
    apiFetch.mockRejectedValue(new ApiError(400, 'bad', 'INVALID_COMPOSE', undefined, ['line 1: not a mapping']))
    fireEvent.click(screen.getByRole('button', { name: 'Create stack' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('line 1: not a mapping')
  })

  describe('form wizard', () => {
    const generated = 'services:\n  web:\n    image: nginx\n'

    async function openWizard() {
      serve(stacks, { role: 'ADMIN' })
      renderPage()
      await screen.findByText('/srv/shop')
      fireEvent.click(screen.getByRole('button', { name: 'Add stack' }))
      fireEvent.click(screen.getByRole('radio', { name: 'Form wizard' }))
    }

    it('generates YAML, previews it and continues in the editor with referenced variables', async () => {
      await openWizard()
      fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'web' } })
      fireEvent.change(screen.getByLabelText('Service 1 name'), { target: { value: 'web' } })
      fireEvent.change(screen.getByLabelText('Service 1 image'), { target: { value: 'nginx' } })
      fireEvent.click(screen.getByRole('button', { name: 'Add env var to service 1' }))
      fireEvent.change(screen.getByLabelText('Service 1 env 1 name'), { target: { value: 'TOKEN' } })
      fireEvent.click(screen.getByLabelText('From stack variable'))
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
})
