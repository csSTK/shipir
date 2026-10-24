import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import type { ReactElement } from 'react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../shared/api/client.ts'
import { ContainersPage } from '../containers/ContainersPage.tsx'
import { ImagesPage } from '../images/ImagesPage.tsx'
import { NetworksPage } from '../networks/NetworksPage.tsx'
import { VolumesPage } from '../volumes/VolumesPage.tsx'

const apiFetch = vi.fn()
vi.mock('../../shared/api/client.ts', async (importActual) => ({
  ...(await importActual<typeof import('../../shared/api/client.ts')>()),
  apiFetch: (...args: unknown[]) => apiFetch(...args),
}))

const statsUnsubscribe = vi.fn()
vi.mock('../../shared/realtime/stomp.ts', () => ({
  subscribeToTopic: () => {
    return statsUnsubscribe
  },
}))

afterEach(() => {
  cleanup()
  apiFetch.mockReset()
})

const cbase = { image: 'x', status: 'Up', health: null, created: '2026-10-01T10:00:00Z', ports: [], composeService: null }
const data: Record<string, unknown> = {
  '/hosts/h1/containers': [
    { ...cbase, id: 'c1', name: 'web', state: 'running', composeProject: 'shop' },
    { ...cbase, id: 'c2', name: 'old', state: 'exited', composeProject: null },
  ],
  '/hosts/h1/images': [{ id: 'sha256:aaaaaaaaaaaaaaaa', repoTags: ['nginx:1'], size: 1, created: '2026-10-01T10:00:00Z', inUse: false }],
  '/hosts/h1/volumes': [{ name: 'pgdata', driver: 'local', mountpoint: '/v', created: null, inUse: false }],
  '/hosts/h1/networks': [{ id: 'n1', name: 'backend', driver: 'bridge', scope: 'local', subnet: null, internal: false, containerCount: 0 }],
  '/hosts/h1/deployments': [{ name: 'shop', containerCount: 1, runningCount: 1, managedDeploymentId: 'd1' }],
}

function serve(role: string, onDelete: (path: string) => Promise<unknown> = () => Promise.resolve(undefined)) {
  apiFetch.mockImplementation((path: string, init?: { method?: string }) => {
    if (path === '/auth/me') return Promise.resolve({ username: 'u', role, setupRequired: false })
    if (init?.method === 'DELETE') return onDelete(path)
    return path in data ? Promise.resolve(data[path]) : Promise.reject(new Error(`unmocked ${path}`))
  })
}

function renderPage(segment: string, page: ReactElement) {
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter initialEntries={[`/environments/h1/${segment}`]}>
        <Routes>
          <Route path={`/environments/:id/${segment}`} element={page} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

const deleteCalls = () => apiFetch.mock.calls.filter(([, init]) => init?.method === 'DELETE').map(([p]) => p)

describe('resource deletion', () => {
  it('hides the delete action from viewers', async () => {
    serve('VIEWER')
    renderPage('containers', <ContainersPage />)
    await screen.findByText('web')
    expect(screen.queryByRole('button', { name: /Delete container/ })).not.toBeInTheDocument()
  })

  it('deletes a stopped container after confirmation, without force option', async () => {
    serve('OPERATOR')
    renderPage('containers', <ContainersPage />)
    fireEvent.click(await screen.findByRole('button', { name: 'Delete container old' }))
    const dialog = screen.getByRole('dialog', { name: 'Delete container' })
    expect(within(dialog).queryByLabelText(/Force/)).not.toBeInTheDocument()
    expect(deleteCalls()).toEqual([])
    fireEvent.click(within(dialog).getByRole('button', { name: 'Delete' }))
    await waitFor(() => expect(deleteCalls()).toEqual(['/hosts/h1/containers/c2?force=false']))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
  })

  it('offers force for a running container and warns about a managed deployment', async () => {
    serve('ADMIN')
    renderPage('containers', <ContainersPage />)
    fireEvent.click(await screen.findByRole('button', { name: 'Delete container web' }))
    const dialog = await screen.findByRole('dialog')
    await waitFor(() => expect(within(dialog).getByRole('note')).toHaveTextContent('Belongs to deployment shop and may come back on the next deploy.'))
    fireEvent.click(within(dialog).getByLabelText('Force (stop and remove)'))
    fireEvent.click(within(dialog).getByRole('button', { name: 'Delete' }))
    await waitFor(() => expect(deleteCalls()).toEqual(['/hosts/h1/containers/c1?force=true']))
  })

  it('shows no managed warning for an unmanaged container', async () => {
    serve('ADMIN')
    renderPage('containers', <ContainersPage />)
    fireEvent.click(await screen.findByRole('button', { name: 'Delete container old' }))
    expect(screen.queryByRole('note')).not.toBeInTheDocument()
  })

  it('keeps the dialog open and shows the server message on RESOURCE_IN_USE', async () => {
    serve('ADMIN', () => Promise.reject(new ApiError(409, JSON.stringify({ code: 'RESOURCE_IN_USE', message: 'Network is used by 2 containers.' }), 'RESOURCE_IN_USE')))
    renderPage('networks', <NetworksPage />)
    fireEvent.click(await screen.findByRole('button', { name: 'Delete network backend' }))
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Delete' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Network is used by 2 containers.')
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })

  it.each([
    ['images', <ImagesPage key="i" />, 'Delete image nginx:1', '/hosts/h1/images/sha256%3Aaaaaaaaaaaaaaaaa'],
    ['volumes', <VolumesPage key="v" />, 'Delete volume pgdata', '/hosts/h1/volumes/pgdata'],
    ['networks', <NetworksPage key="n" />, 'Delete network backend', '/hosts/h1/networks/n1'],
  ])('deletes from the %s page', async (segment, page, label, expected) => {
    serve('OPERATOR')
    renderPage(segment, page)
    fireEvent.click(await screen.findByRole('button', { name: label }))
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Delete' }))
    await waitFor(() => expect(deleteCalls()).toEqual([expected]))
  })
})
