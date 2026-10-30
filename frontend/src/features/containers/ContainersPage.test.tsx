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

function mockWithRole(role: string) {
  apiFetch.mockImplementation((path: string) =>
    Promise.resolve(path === '/auth/me' ? { username: 'u', role, setupRequired: false } : path.endsWith('/containers') ? containers : []),
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
  return screen.getAllByRole('link', { name: /^Details of / }).map((l) => l.textContent)
}

async function openMenu(name: string) {
  fireEvent.pointerDown(await screen.findByRole('button', { name: `More actions for ${name}` }), { button: 0, ctrlKey: false })
  return screen.findByRole('menu')
}

describe('ContainersPage', () => {
  it('opens the publish dialog for a container from the row menu (operators), locked to that container', async () => {
    mockWithRole('OPERATOR')
    renderAt('/environments/h1/containers')
    const menu = await openMenu('web')
    fireEvent.click(within(menu).getByRole('menuitem', { name: 'Publish with a domain' }))
    const dialog = await screen.findByRole('dialog', { name: 'Publish a container' })
    expect(within(dialog).getByText('web')).toBeInTheDocument()
    expect(within(dialog).queryByRole('radio')).not.toBeInTheDocument()
  })

  it('does not offer publishing to viewers', async () => {
    mockWithRole('VIEWER')
    renderAt('/environments/h1/containers', 'VIEWER')
    const menu = await openMenu('web')
    expect(within(menu).queryByRole('menuitem', { name: 'Publish with a domain' })).not.toBeInTheDocument()
  })

  it('loads the containers of the environment and lists name with image, state with health, deployment, ports', async () => {
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
    expect(within(row).getByText('nginx:1')).toBeInTheDocument()
  })

  it('hides Created by default and offers it in the column menu', async () => {
    mockContainers()
    renderAt('/environments/h1/containers')
    await screen.findByText('nginx:1')
    expect(screen.queryByRole('columnheader', { name: /Created/ })).toBeNull()
    fireEvent.pointerDown(screen.getByRole('button', { name: 'Columns' }), { button: 0, ctrlKey: false })
    expect(await screen.findByRole('menuitem', { name: 'Created' })).toBeInTheDocument()
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
    const rowButtons = (row: string) =>
      within(screen.getByRole('row', { name: new RegExp(row) }))
        .getAllByRole('button')
        .map((b) => b.getAttribute('aria-label'))
    const menuItems = () => within(screen.getByRole('menu')).getAllByRole('menuitem').map((i) => i.textContent)

    it('offers icon buttons and menu entries matching the container state', async () => {
      apiFetch.mockImplementation((path: string) =>
        path === '/auth/me' ? Promise.resolve({ username: 'u', role: 'ADMIN', setupRequired: false }) : Promise.resolve([...containers, paused]),
      )
      renderAt('/environments/h1/containers')
      await screen.findByRole('button', { name: 'Start api' })
      expect(rowButtons('web')).toEqual(['Stop web', 'Logs web', 'Console web', 'More actions for web'])
      expect(rowButtons('api')).toEqual(['Start api', 'Logs api', 'More actions for api'])
      expect(rowButtons('frozen')).toEqual(['Unpause frozen', 'Logs frozen', 'More actions for frozen'])
      await openMenu('web')
      expect(menuItems()).toEqual(['Restart', 'Pause', 'Details', 'Publish with a domain', 'Kill', 'Delete'])
      fireEvent.keyDown(screen.getByRole('menu'), { key: 'Escape' })
      await vi.waitFor(() => expect(screen.queryByRole('menu')).toBeNull())
      await openMenu('api')
      expect(menuItems()).toEqual(['Details', 'Publish with a domain', 'Delete'])
    })

    describe('console action', () => {
      function mockCaps(operatorEnabled: boolean, scope = { hostOperable: true, operableProjects: [] as string[] }) {
        apiFetch.mockImplementation((path: string) => {
          if (path === '/auth/me') return Promise.resolve({ username: 'u', role: apiFetchRole, setupRequired: false })
          if (path === '/exec/capabilities') return Promise.resolve({ operatorEnabled })
          if (path === '/exec/access?hostId=h1') return Promise.resolve(scope)
          return Promise.resolve(path === '/hosts/h1/containers' ? containers : [])
        })
      }

      it('shows Console to an OPERATOR when operator exec is enabled', async () => {
        mockCaps(true)
        renderAt('/environments/h1/containers', 'OPERATOR')
        expect(await screen.findByRole('button', { name: 'Console web' })).toBeInTheDocument()
      })

      it('hides Console from an OPERATOR when operator exec is disabled', async () => {
        mockCaps(false)
        renderAt('/environments/h1/containers', 'OPERATOR')
        await screen.findByRole('button', { name: 'Logs web' })
        expect(screen.queryByRole('button', { name: 'Console web' })).toBeNull()
      })

      it('hides Console from an OPERATOR with only a VIEW grant (no host or deployment OPERATE)', async () => {
        mockCaps(true, { hostOperable: false, operableProjects: [] })
        renderAt('/environments/h1/containers', 'OPERATOR')
        await screen.findByRole('button', { name: 'Logs web' })
        await vi.waitFor(() => expect(apiFetch.mock.calls.map((c) => c[0])).toContain('/exec/access?hostId=h1'))
        expect(screen.queryByRole('button', { name: 'Console web' })).toBeNull()
      })

      it('shows Console only for containers of a deployment the OPERATOR may operate', async () => {
        mockCaps(true, { hostOperable: false, operableProjects: ['shop'] })
        renderAt('/environments/h1/containers', 'OPERATOR')
        expect(await screen.findByRole('button', { name: 'Console web' })).toBeInTheDocument()
        cleanup()
        mockCaps(true, { hostOperable: false, operableProjects: ['other'] })
        renderAt('/environments/h1/containers', 'OPERATOR')
        await screen.findByRole('button', { name: 'Logs web' })
        await vi.waitFor(() => expect(apiFetch.mock.calls.map((c) => c[0])).toContain('/exec/access?hostId=h1'))
        expect(screen.queryByRole('button', { name: 'Console web' })).toBeNull()
      })

      it('never shows Console to a VIEWER, even when operator exec is enabled', async () => {
        mockCaps(true)
        renderAt('/environments/h1/containers', 'VIEWER')
        await screen.findByRole('button', { name: 'Logs web' })
        expect(screen.queryByRole('button', { name: 'Console web' })).toBeNull()
        expect(apiFetch.mock.calls.map((c) => c[0])).not.toContain('/exec/capabilities')
      })
    })

    it('hides lifecycle actions for viewers but keeps logs and details', async () => {
      mockApi()
      renderAt('/environments/h1/containers', 'VIEWER')
      await screen.findByText('nginx:1')
      expect(rowButtons('web')).toEqual(['Logs web', 'More actions for web'])
      await openMenu('web')
      expect(menuItems()).toEqual(['Details'])
    })

    it('shows the pending label at once, posts and refetches', async () => {
      let resolve!: () => void
      mockApi(() => new Promise<void>((r) => (resolve = r)))
      renderAt('/environments/h1/containers')
      await screen.findByRole('button', { name: 'Stop web' })
      await openMenu('web')
      fireEvent.click(screen.getByRole('menuitem', { name: 'Restart' }))
      const row = await screen.findByRole('row', { name: /web/ })
      expect(await within(row).findByText('Restarting…')).toBeInTheDocument()
      expect(await screen.findByRole('button', { name: 'Stop web' })).toBeDisabled()
      expect(apiFetch).toHaveBeenCalledWith('/hosts/h1/containers/1/restart', { method: 'POST' })
      const before = apiFetch.mock.calls.filter((c) => c[0] === '/hosts/h1/containers').length
      resolve()
      await vi.waitFor(() => expect(apiFetch.mock.calls.filter((c) => c[0] === '/hosts/h1/containers').length).toBeGreaterThan(before))
    })

    it('asks before killing', async () => {
      mockApi()
      renderAt('/environments/h1/containers')
      await screen.findByRole('button', { name: 'Stop web' })
      await openMenu('web')
      fireEvent.click(screen.getByRole('menuitem', { name: 'Kill' }))
      expect(apiFetch).not.toHaveBeenCalledWith(expect.stringContaining('/kill'), expect.anything())
      const dialog = await screen.findByRole('dialog')
      fireEvent.click(within(dialog).getByRole('button', { name: 'Kill' }))
      await vi.waitFor(() => expect(apiFetch).toHaveBeenCalledWith('/hosts/h1/containers/1/kill', { method: 'POST' }))
      await vi.waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    })

    it('asks before deleting', async () => {
      mockApi()
      renderAt('/environments/h1/containers')
      await screen.findByRole('button', { name: 'Stop web' })
      await openMenu('web')
      fireEvent.click(screen.getByRole('menuitem', { name: 'Delete' }))
      const dialog = await screen.findByRole('dialog')
      expect(within(dialog).getByText(/This cannot be undone/)).toBeInTheDocument()
    })

    it('shows INVALID_CONTAINER_STATE and 502 errors inline', async () => {
      mockApi(() => Promise.reject(new ApiError(409, '{}', 'INVALID_CONTAINER_STATE')))
      renderAt('/environments/h1/containers')
      fireEvent.click(await screen.findByRole('button', { name: 'Stop web' }))
      expect(await screen.findByRole('alert')).toHaveTextContent('not in a state that allows this action')
      cleanup()
      mockApi(() => Promise.reject(new ApiError(502, '{}', 'UNREACHABLE')))
      renderAt('/environments/h1/containers')
      fireEvent.click(await screen.findByRole('button', { name: 'Stop web' }))
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
