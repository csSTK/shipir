import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../shared/api/client.ts'
import { DeploymentDetailPage } from './DeploymentDetailPage.tsx'

vi.mock('../../shared/realtime/stomp.ts', () => ({ subscribeToTopic: () => () => {} }))

const apiFetch = vi.fn()
vi.mock('../../shared/api/client.ts', async (importActual) => ({
  ...(await importActual<typeof import('../../shared/api/client.ts')>()),
  apiFetch: (...args: unknown[]) => apiFetch(...args),
}))

afterEach(() => {
  cleanup()
  apiFetch.mockReset()
})

const BASE = '/hosts/h1/managed-deployments/s1'
const deployment = {
  id: 's1',
  name: 'shop',
  status: 'RUNNING',
  source: 'FILE',
  lastDeployedAt: '2026-10-02T10:00:00Z',
  createdAt: '2026-10-01T10:00:00Z',
  yaml: 'services:\n  web:\n    image: nginx',
  envKeys: ['DB_PASSWORD'],
}
const versions = [
  { id: 'v3', source: 'MANUAL', commitSha: null, createdBy: 'ann', createdAt: '2026-10-03T10:00:00Z', current: false, deployed: false },
  { id: 'v2', source: 'MANUAL', commitSha: null, createdBy: 'ann', createdAt: '2026-10-02T10:00:00Z', current: true, deployed: true },
  { id: 'v1', source: 'GIT', commitSha: 'abcdef1234', createdBy: null, createdAt: '2026-10-01T10:00:00Z', current: false, deployed: true },
]

type Handler = (init?: { method?: string }) => unknown
function serve(role: string, overrides: Record<string, Handler> = {}) {
  apiFetch.mockImplementation((path: string, init?: { method?: string }) => {
    const key = `${init?.method ?? 'GET'} ${path}`
    if (overrides[key]) {
      const r = overrides[key](init)
      return r instanceof Error ? Promise.reject(r) : Promise.resolve(r)
    }
    if (path === '/auth/me') return Promise.resolve({ username: 'u', role, setupRequired: false })
    if (path === BASE) return Promise.resolve(deployment)
    if (path === `${BASE}/versions`) return Promise.resolve(versions)
    return Promise.reject(new ApiError(404, 'x', 'NOT_FOUND'))
  })
}

function Where() {
  return <p data-testid="where">{useLocation().pathname}</p>
}

function renderPage() {
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter initialEntries={['/environments/h1/deployments/s1']}>
        <Routes>
          <Route path="/environments/:id/deployments/:deploymentId" element={<DeploymentDetailPage />} />
          <Route path="/environments/:id/deployments" element={<Where />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('DeploymentDetailPage', () => {
  it('shows status, content and versions with current and draft markers', async () => {
    serve('ADMIN')
    renderPage()
    expect(screen.getByRole('status', { name: 'Loading deployment' })).toBeInTheDocument()
    expect(await screen.findByRole('heading', { name: 'shop' })).toBeInTheDocument()
    expect(screen.getByText('Running')).toBeInTheDocument()
    expect(await screen.findByLabelText('Compose file')).toHaveDisplayValue(/image: nginx/)
    expect(screen.getByText('DB_PASSWORD')).toBeInTheDocument()
    const list = await screen.findByRole('list', { name: 'Versions' })
    const items = within(list).getAllByRole('listitem')
    expect(items).toHaveLength(3)
    expect(within(items[0]).getByText('Draft')).toBeInTheDocument()
    expect(within(items[1]).getByText('Current')).toBeInTheDocument()
    expect(within(items[2]).getByText('Git · abcdef1')).toBeInTheDocument()
  })

  it('hides all write actions from viewers', async () => {
    serve('VIEWER')
    renderPage()
    await screen.findByRole('list', { name: 'Versions' })
    for (const name of ['Deploy', 'Stop', 'Delete']) {
      expect(screen.queryByRole('button', { name })).not.toBeInTheDocument()
    }
    expect(screen.queryByRole('button', { name: /Roll back/ })).not.toBeInTheDocument()
  })

  it('shows not found for an unknown deployment', async () => {
    serve('ADMIN', { [`GET ${BASE}`]: () => new ApiError(404, 'x', 'DEPLOYMENT_NOT_FOUND') })
    renderPage()
    expect(await screen.findByRole('heading', { name: 'Deployment not found' })).toBeInTheDocument()
  })

  it('shows an error with retry for other failures', async () => {
    serve('ADMIN', { [`GET ${BASE}`]: () => new ApiError(500, 'boom', 'INTERNAL') })
    renderPage()
    expect(await screen.findByRole('button', { name: 'Retry' })).toBeInTheDocument()
  })

  it('deploys and shows the started deployment', async () => {
    serve('OPERATOR', { [`POST ${BASE}/deploy`]: () => ({ runId: 'd-42', versionId: 'v3' }) })
    renderPage()
    fireEvent.click(await screen.findByRole('button', { name: 'Deploy' }))
    expect(await screen.findByLabelText('Deployment output')).toBeInTheDocument()
    expect(screen.getByText('d-42')).toBeInTheDocument()
  })

  it('maps DEPLOYMENT_IN_PROGRESS to a message', async () => {
    serve('OPERATOR', { [`POST ${BASE}/deploy`]: () => new ApiError(409, 'busy', 'DEPLOYMENT_IN_PROGRESS') })
    renderPage()
    fireEvent.click(await screen.findByRole('button', { name: 'Deploy' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('already in progress')
  })

  it('offers rollback only for deployed, non-current versions and confirms first', async () => {
    const rollback = vi.fn(() => ({ runId: 'd-9', versionId: 'v1' }))
    serve('ADMIN', { [`POST ${BASE}/versions/v1/rollback`]: rollback })
    renderPage()
    await screen.findByRole('list', { name: 'Versions' })
    expect(screen.getAllByRole('button', { name: /Roll back to/ })).toHaveLength(1)
    fireEvent.click(screen.getByRole('button', { name: /Roll back to/ }))
    expect(rollback).not.toHaveBeenCalled()
    const dialog = screen.getByRole('dialog', { name: 'Roll back deployment' })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Roll back' }))
    expect(await screen.findByLabelText('Deployment output')).toBeInTheDocument()
    expect(rollback).toHaveBeenCalled()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('stops after confirmation', async () => {
    const stop = vi.fn(() => ({ ...deployment, status: 'STOPPED' }))
    serve('ADMIN', { [`POST ${BASE}/stop`]: stop })
    renderPage()
    fireEvent.click(await screen.findByRole('button', { name: 'Stop' }))
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Stop' }))
    await waitFor(() => expect(stop).toHaveBeenCalled())
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
  })

  it('deletes after confirmation and returns to the list', async () => {
    serve('ADMIN', { [`DELETE ${BASE}`]: () => undefined })
    renderPage()
    fireEvent.click(await screen.findByRole('button', { name: 'Delete' }))
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Delete' }))
    await waitFor(() => expect(screen.getByTestId('where')).toHaveTextContent('/environments/h1/deployments'))
  })

  it('keeps the dialog open with a message when compose down fails', async () => {
    serve('ADMIN', { [`DELETE ${BASE}`]: () => new ApiError(502, 'x', 'COMPOSE_DOWN_FAILED') })
    renderPage()
    fireEvent.click(await screen.findByRole('button', { name: 'Delete' }))
    const dialog = screen.getByRole('dialog')
    fireEvent.click(within(dialog).getByRole('button', { name: 'Delete' }))
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('not deleted')
  })

  it('closes dialogs with Escape', async () => {
    serve('ADMIN')
    renderPage()
    fireEvent.click(await screen.findByRole('button', { name: 'Delete' }))
    fireEvent.keyDown(screen.getByRole('dialog'), { key: 'Escape' })
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  describe('editing', () => {
    const putKey = `PUT ${BASE}`
    const envKey = `GET ${BASE}/env`

    async function startEdit() {
      renderPage()
      fireEvent.click(await screen.findByRole('button', { name: 'Edit' }))
    }

    it('saves a changed Compose file and keeps stored variables (env null)', async () => {
      const put = vi.fn(() => deployment)
      serve('OPERATOR', { [putKey]: put })
      await startEdit()
      fireEvent.change(await screen.findByLabelText('Compose file'), { target: { value: 'services: {}' } })
      fireEvent.click(screen.getByRole('button', { name: 'Save' }))
      expect(await screen.findByRole('status')).toHaveTextContent('Saved as a draft')
      expect(apiFetch).toHaveBeenCalledWith(BASE, { method: 'PUT', body: { yaml: 'services: {}', env: null } })
    })

    it('loads values on request, masks them and sends the edited list', async () => {
      const put = vi.fn(() => deployment)
      serve('ADMIN', { [putKey]: put, [envKey]: () => ({ entries: [{ key: 'DB_PASSWORD', value: 's3cret' }] }) })
      await startEdit()
      fireEvent.click(screen.getByRole('button', { name: 'Edit variables' }))
      const value = await screen.findByLabelText('Variable 1 value')
      expect(value).toHaveAttribute('type', 'password')
      fireEvent.click(screen.getByRole('button', { name: 'Show values' }))
      expect(value).toHaveAttribute('type', 'text')
      fireEvent.change(value, { target: { value: 'new' } })
      fireEvent.click(screen.getByRole('button', { name: 'Save' }))
      await waitFor(() =>
        expect(apiFetch).toHaveBeenCalledWith(BASE, {
          method: 'PUT',
          body: { yaml: deployment.yaml, env: [{ key: 'DB_PASSWORD', value: 'new' }] },
        }),
      )
    })

    it('shows INVALID_COMPOSE details inline and stays in edit mode', async () => {
      serve('OPERATOR', { [putKey]: () => new ApiError(400, 'bad', 'INVALID_COMPOSE', undefined, ['services.web: image required']) })
      await startEdit()
      fireEvent.click(screen.getByRole('button', { name: 'Save' }))
      const alert = await screen.findByRole('alert')
      expect(alert).toHaveTextContent('The Compose file is not valid.')
      expect(alert).toHaveTextContent('services.web: image required')
      expect(screen.getByRole('button', { name: 'Save' })).toBeInTheDocument()
    })

    it('blocks saving while a variable name is invalid', async () => {
      serve('OPERATOR', { [envKey]: () => ({ entries: [{ key: '1bad', value: 'x' }] }) })
      await startEdit()
      fireEvent.click(screen.getByRole('button', { name: 'Edit variables' }))
      await screen.findByLabelText('Variable 1 name')
      fireEvent.click(screen.getByRole('button', { name: 'Save' }))
      expect(await screen.findAllByRole('alert')).not.toHaveLength(0)
      expect(apiFetch).not.toHaveBeenCalledWith(BASE, expect.objectContaining({ method: 'PUT' }))
    })

    it('does not offer Edit to viewers', async () => {
      serve('VIEWER')
      renderPage()
      await screen.findByRole('heading', { name: 'shop' })
      expect(screen.queryByRole('button', { name: 'Edit' })).not.toBeInTheDocument()
    })
  })
})
