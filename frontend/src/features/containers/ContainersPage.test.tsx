import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../shared/api/client.ts'
import { ContainersPage } from './ContainersPage.tsx'

const apiFetch = vi.fn()
vi.mock('../../shared/api/client.ts', async (importActual) => ({
  ...(await importActual<typeof import('../../shared/api/client.ts')>()),
  apiFetch: (...args: unknown[]) => apiFetch(...args),
}))

afterEach(() => {
  cleanup()
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

function renderAt(url: string) {
  render(
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
    apiFetch.mockResolvedValue(containers)
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
    apiFetch.mockResolvedValue(containers)
    renderAt('/environments/h1/containers')
    await screen.findByText('nginx:1')
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'redis' } })
    expect(names()).toEqual(['cache'])
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'shop' } })
    expect(names()).toEqual(['api', 'web'])
  })

  it('sorts by name descending when the header is clicked', async () => {
    apiFetch.mockResolvedValue(containers)
    renderAt('/environments/h1/containers')
    await screen.findByText('nginx:1')
    fireEvent.click(screen.getByRole('button', { name: 'Name' }))
    expect(names()).toEqual(['web', 'cache', 'api'])
  })

  it('filters by the deployment query parameter and clears it', async () => {
    apiFetch.mockResolvedValue(containers)
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
})
