import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../shared/api/client.ts'
import { ContainersPage } from './ContainersPage.tsx'

let apiFetchRole = 'OPERATOR'
const apiFetch = vi.fn()
vi.mock('../../shared/api/client.ts', async (importActual) => ({
  ...(await importActual<typeof import('../../shared/api/client.ts')>()),
  apiFetch: (...args: unknown[]) => apiFetch(...args),
}))

let statsHandlers: { onMessage: (body: string) => void } | null = null
const statsUnsubscribe = vi.fn()
vi.mock('../../shared/realtime/stomp.ts', () => ({
  subscribeToTopic: (_dest: string, h: { onMessage: (body: string) => void }) => {
    statsHandlers = h
    return statsUnsubscribe
  },
}))

afterEach(() => {
  cleanup()
  statsUnsubscribe.mockReset()
  apiFetch.mockReset()
})

const base = {
  status: 'Up 2 hours',
  health: null,
  created: '2026-10-01T10:00:00Z',
  ports: [],
  composeProject: null,
  composeService: null,
}
const containers = [
  { ...base, id: '1', name: 'web', image: 'nginx:1', state: 'running', health: 'healthy', ports: ['8080:80/tcp'], composeProject: 'shop', composeService: 'web' },
  { ...base, id: '2', name: 'api', image: 'app:2', state: 'exited', composeProject: 'shop', composeService: 'api' },
  { ...base, id: '3', name: 'cache', image: 'redis:7', state: 'running', health: 'unhealthy' },
]

/** Routes the three lists the page reads; `extra` overrides deployments/images. */
function mockContainers(extra: { deployments?: unknown[]; images?: unknown[] } = {}) {
  apiFetch.mockImplementation((path: string) =>
    Promise.resolve(path.endsWith('/containers') ? containers : path.endsWith('/images') ? (extra.images ?? []) : (extra.deployments ?? [])),
  )
}

function renderAt(url: string, role = 'OPERATOR') {
  apiFetchRole = role
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter initialEntries={[url]}>
        <Routes>
          <Route path="/environments/:id/containers" element={<ContainersPage />} />
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

describe('ContainersPage', () => {
  it('loads the containers of the environment and lists name, state, health, image, deployment, ports', async () => {
    mockContainers()
    renderAt('/environments/h1/containers')
    expect(screen.getByRole('status', { name: 'Loading containers' })).toBeInTheDocument()
    expect(await screen.findByText('nginx:1')).toBeInTheDocument()
    expect(apiFetch).toHaveBeenCalledWith('/hosts/h1/containers')
    expect(names()).toEqual(['api', 'cache', 'web'])
    const row = screen.getByRole('row', { name: /web/ })
    expect(within(row).getByText('running')).toBeInTheDocument()
    expect(within(row).getByText('healthy')).toBeInTheDocument()
    expect(within(row).getByText('shop')).toBeInTheDocument()
    expect(within(row).getByText('8080:80/tcp')).toBeInTheDocument()
  })

  it('searches by name, image and deployment', async () => {
    mockContainers()
    renderAt('/environments/h1/containers')
    await screen.findByText('nginx:1')
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'redis' } })
    expect(names()).toEqual(['cache'])
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'shop' } })
    expect(names()).toEqual(['api', 'web'])
  })

  it('sorts by name descending when the header is clicked', async () => {
    mockContainers()
    renderAt('/environments/h1/containers')
    await screen.findByText('nginx:1')
    fireEvent.click(screen.getByRole('button', { name: 'Name' }))
    expect(names()).toEqual(['web', 'cache', 'api'])
  })

  it('filters by the deployment query parameter and clears it', async () => {
    mockContainers()
    renderAt('/environments/h1/containers?deployment=shop')
    await screen.findByText('nginx:1')
    expect(names()).toEqual(['api', 'web'])
    fireEvent.click(screen.getByRole('button', { name: /Deployment: shop/ }))
    expect(names()).toEqual(['api', 'cache', 'web'])
  })

  it('maps UNREACHABLE and TIMEOUT to messages', async () => {
    apiFetch.mockRejectedValue(new ApiError(502, 'x', 'TIMEOUT'))
    renderAt('/environments/h1/containers')
    expect(await screen.findByRole('alert')).toHaveTextContent('The connection timed out.')
  })

  it('shows the empty state', async () => {
    apiFetch.mockResolvedValue([])
    renderAt('/environments/h1/containers')
    expect(await screen.findByText('No containers in this environment.')).toBeInTheDocument()
  })

  describe('lifecycle actions', () => {
    function mockApi(post: () => Promise<unknown> = () => Promise.resolve(undefined)) {
      apiFetch.mockImplementation((path: string, init?: { method?: string }) => {
        if (path === '/auth/me') return Promise.resolve({ username: 'u', role: apiFetchRole, setupRequired: false })
        if (init?.method === 'POST') return post()
        if (path === '/hosts/h1/containers') return Promise.resolve(containers)
        return Promise.resolve([])
      })
    }
    const paused = { ...containers[0], id: '4', name: 'frozen', state: 'paused' }
    const buttons = (row: string) =>
      within(screen.getByRole('row', { name: new RegExp(row) }))
        .getAllByRole('button')
        .map((b) => b.textContent)

    it('offers buttons matching the container state', async () => {
      apiFetch.mockImplementation((path: string) =>
        path === '/auth/me' ? Promise.resolve({ username: 'u', role: 'OPERATOR', setupRequired: false }) : Promise.resolve([...containers, paused]),
      )
      renderAt('/environments/h1/containers')
      await screen.findByRole('button', { name: 'Start container api' })
      expect(buttons('web')).toEqual(['Stop', 'Restart', 'Pause', 'Kill', 'Delete'])
      expect(buttons('api')).toEqual(['Start', 'Delete'])
      expect(buttons('frozen')).toEqual(['Unpause', 'Delete'])
    })

    it('hides actions for viewers', async () => {
      mockApi()
      renderAt('/environments/h1/containers', 'VIEWER')
      await screen.findByText('nginx:1')
      expect(screen.queryByRole('button', { name: /container web/ })).toBeNull()
    })

    it('shows the pending label at once, posts and refetches', async () => {
      let resolve!: () => void
      mockApi(() => new Promise<void>((r) => (resolve = r)))
      renderAt('/environments/h1/containers')
      fireEvent.click(await screen.findByRole('button', { name: 'Restart container web' }))
      expect(await screen.findByRole('button', { name: 'Restart container web' })).toHaveTextContent('Restarting…')
      expect(screen.getByRole('button', { name: 'Stop container web' })).toBeDisabled()
      expect(apiFetch).toHaveBeenCalledWith('/hosts/h1/containers/1/restart', { method: 'POST' })
      const before = apiFetch.mock.calls.filter((c) => c[0] === '/hosts/h1/containers').length
      resolve()
      await vi.waitFor(() => expect(apiFetch.mock.calls.filter((c) => c[0] === '/hosts/h1/containers').length).toBeGreaterThan(before))
    })

    it('asks before killing', async () => {
      mockApi()
      renderAt('/environments/h1/containers')
      fireEvent.click(await screen.findByRole('button', { name: 'Kill container web' }))
      expect(apiFetch).not.toHaveBeenCalledWith(expect.stringContaining('/kill'), expect.anything())
      const dialog = screen.getByRole('dialog')
      fireEvent.click(within(dialog).getByRole('button', { name: 'Kill' }))
      await vi.waitFor(() => expect(apiFetch).toHaveBeenCalledWith('/hosts/h1/containers/1/kill', { method: 'POST' }))
      await vi.waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    })

    it('shows INVALID_CONTAINER_STATE and 502 errors inline', async () => {
      mockApi(() => Promise.reject(new ApiError(409, '{}', 'INVALID_CONTAINER_STATE')))
      renderAt('/environments/h1/containers')
      fireEvent.click(await screen.findByRole('button', { name: 'Stop container web' }))
      expect(await screen.findByRole('alert')).toHaveTextContent('not in a state that allows this action')
      cleanup()
      mockApi(() => Promise.reject(new ApiError(502, '{}', 'UNREACHABLE')))
      renderAt('/environments/h1/containers')
      fireEvent.click(await screen.findByRole('button', { name: 'Stop container web' }))
      expect(await screen.findByRole('alert')).toHaveTextContent('Could not reach the Docker host.')
    })
  })

  it('shows live CPU and memory with a sparkline for running containers and dashes otherwise', async () => {
    mockContainers()
    const { unmount } = renderAt('/environments/h1/containers')
    await screen.findByText('nginx:1')
    const sample = (cpu: number) => ({ containerId: '1', cpu, memUsed: 64 * 1024 * 1024, memLimit: 512 * 1024 * 1024, netRx: 0, netTx: 0, blkRead: 0, blkWrite: 0 })
    act(() => statsHandlers!.onMessage(JSON.stringify({ seq: 1, samples: [sample(10)] })))
    act(() => statsHandlers!.onMessage(JSON.stringify({ seq: 2, samples: [sample(12.34)] })))
    const web = screen.getByRole('row', { name: /web/ })
    expect(within(web).getByText('12.3%')).toBeInTheDocument()
    expect(within(web).getByText('64 MiB')).toBeInTheDocument()
    expect(within(web).getByRole('img', { name: 'CPU history of web' })).toBeInTheDocument()
    const api = screen.getByRole('row', { name: /api/ })
    expect(within(api).queryByRole('img')).not.toBeInTheDocument()
    expect(within(api).getAllByText('–').length).toBeGreaterThanOrEqual(2)
    unmount()
    expect(statsUnsubscribe).toHaveBeenCalled()
  })

  it('shows an update badge from the deployment flag, linking to the managed deployment', async () => {
    mockContainers({ deployments: [{ name: 'shop', updateAvailable: true, managedDeploymentId: 'm1' }] })
    renderAt('/environments/h1/containers')
    await screen.findByText('nginx:1')
    const link = within(screen.getByRole('row', { name: /web/ })).getByRole('link', { name: 'Update available for web' })
    expect(link).toHaveAttribute('href', '/environments/h1/deployments/m1')
    expect(within(screen.getByRole('row', { name: /cache/ })).queryByText('Update available')).not.toBeInTheDocument()
  })

  it('shows an update badge from the image flag for containers outside Compose', async () => {
    mockContainers({ images: [{ id: 'x', repoTags: ['redis:7'], updateAvailable: true }] })
    renderAt('/environments/h1/containers')
    await screen.findByText('redis:7')
    const link = within(screen.getByRole('row', { name: /cache/ })).getByRole('link', { name: 'Update available for cache' })
    expect(link).toHaveAttribute('href', '/environments/h1/images')
    expect(screen.getAllByText('Update available')).toHaveLength(1)
  })
})
