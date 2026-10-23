import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../shared/api/client.ts'

import { VolumesPage } from './VolumesPage.tsx'

const apiFetch = vi.fn()
vi.mock('../../shared/api/client.ts', async (importActual) => ({
  ...(await importActual<typeof import('../../shared/api/client.ts')>()),
  apiFetch: (...args: unknown[]) => apiFetch(...args),
}))

afterEach(() => {
  cleanup()
  apiFetch.mockReset()
})

const volumes = [
  { name: 'pgdata', driver: 'local', mountpoint: '/var/lib/docker/volumes/pgdata/_data', created: '2026-10-01T10:00:00Z', inUse: true },
  { name: 'cache', driver: 'local', mountpoint: '/var/lib/docker/volumes/cache/_data', created: null, inUse: false },
]

function renderPage() {
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter initialEntries={['/environments/h1/volumes']}>
        <Routes>
          <Route path="/environments/:id/volumes" element={<VolumesPage />} />
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

describe('VolumesPage', () => {
  it('lists name, driver, created and usage badge', async () => {
    apiFetch.mockResolvedValue(volumes)
    renderPage()
    expect(screen.getByRole('status', { name: 'Loading volumes' })).toBeInTheDocument()
    expect(await screen.findByText('pgdata')).toBeInTheDocument()
    expect(apiFetch).toHaveBeenCalledWith('/hosts/h1/volumes')
    expect(firstCells()).toEqual(['cache', 'pgdata'])
    expect(within(screen.getByRole('row', { name: /pgdata/ })).getByText('In use')).toBeInTheDocument()
    expect(within(screen.getByRole('row', { name: /cache/ })).getByText('Unused')).toBeInTheDocument()
  })

  it('renders a missing created date as a dash', async () => {
    apiFetch.mockResolvedValue(volumes)
    renderPage()
    const row = (await screen.findByText('cache')).closest('tr')!
    expect(within(row).getByText('–')).toBeInTheDocument()
    expect(within(row).queryByText(/Invalid/)).toBeNull()
  })

  it('searches and sorts by name descending', async () => {
    apiFetch.mockResolvedValue(volumes)
    renderPage()
    await screen.findByText('pgdata')
    fireEvent.click(screen.getByRole('button', { name: 'Name' }))
    expect(firstCells()).toEqual(['pgdata', 'cache'])
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'pg' } })
    expect(firstCells()).toEqual(['pgdata'])
  })

  it('maps errors and shows the empty state', async () => {
    apiFetch.mockRejectedValue(new ApiError(404, 'x', 'HOST_NOT_FOUND'))
    renderPage()
    expect(await screen.findByRole('alert')).toHaveTextContent('This environment does not exist or was deleted.')
    cleanup()
    apiFetch.mockResolvedValue([])
    renderPage()
    expect(await screen.findByText('No volumes in this environment.')).toBeInTheDocument()
  })
})
