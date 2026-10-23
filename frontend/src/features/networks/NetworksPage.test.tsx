import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../shared/api/client.ts'

import { NetworksPage } from './NetworksPage.tsx'

const apiFetch = vi.fn()
vi.mock('../../shared/api/client.ts', async (importActual) => ({
  ...(await importActual<typeof import('../../shared/api/client.ts')>()),
  apiFetch: (...args: unknown[]) => apiFetch(...args),
}))

afterEach(() => {
  cleanup()
  apiFetch.mockReset()
})

const networks = [
  { id: '1', name: 'bridge', driver: 'bridge', scope: 'local', subnet: '172.17.0.0/16', internal: false, containerCount: 2 },
  { id: '2', name: 'backend', driver: 'overlay', scope: 'swarm', subnet: null, internal: true, containerCount: 0 },
  { id: '3', name: 'host', driver: 'host', scope: 'local', subnet: null, internal: false, containerCount: 1 },
]

function renderPage() {
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter initialEntries={['/environments/h1/networks']}>
        <Routes>
          <Route path="/environments/:id/networks" element={<NetworksPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

function firstCells() {
  return screen
    .getAllByRole('row')
    .slice(1)
    .map((r) => within(r).getAllByRole('cell')[0].textContent)
}

describe('NetworksPage', () => {
  it('lists name, driver, scope, subnet and container count', async () => {
    apiFetch.mockResolvedValue(networks)
    renderPage()
    expect(screen.getByRole('status', { name: 'Loading networks' })).toBeInTheDocument()
    expect(await screen.findByText('172.17.0.0/16')).toBeInTheDocument()
    expect(apiFetch).toHaveBeenCalledWith('/hosts/h1/networks')
    expect(firstCells()).toEqual(['backend Internal', 'bridge', 'host'])
    const row = screen.getByRole('row', { name: /bridge/ })
    expect(within(row).getByText('local')).toBeInTheDocument()
    expect(within(row).getByText('2')).toBeInTheDocument()
  })

  it('marks internal networks and shows a dash without subnet', async () => {
    apiFetch.mockResolvedValue(networks)
    renderPage()
    const row = (await screen.findByRole('row', { name: /backend/ }))
    expect(within(row).getByText('Internal')).toBeInTheDocument()
    expect(within(row).getByText('–')).toBeInTheDocument()
  })

  it('searches and sorts by container count descending', async () => {
    apiFetch.mockResolvedValue(networks)
    renderPage()
    await screen.findByText('172.17.0.0/16')
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'overlay' } })
    expect(firstCells()).toEqual(['backend Internal'])
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: '' } })
    fireEvent.click(screen.getByRole('button', { name: 'Containers' }))
    fireEvent.click(screen.getByRole('button', { name: 'Containers' }))
    expect(firstCells()).toEqual(['bridge', 'host', 'backend Internal'])
  })

  it('maps errors and shows the empty state', async () => {
    apiFetch.mockRejectedValue(new ApiError(502, 'x', 'TIMEOUT'))
    renderPage()
    expect(await screen.findByRole('alert')).toHaveTextContent('The connection timed out.')
    cleanup()
    apiFetch.mockResolvedValue([])
    renderPage()
    expect(await screen.findByText('No networks in this environment.')).toBeInTheDocument()
  })
})
