import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { apiFetch } from '../../shared/api/client.ts'
import { EnvironmentsOverview } from './EnvironmentsOverview.tsx'
import type { HostOverview } from './api.ts'

vi.mock('../../shared/api/client.ts', async (importActual) => ({
  ...(await importActual<typeof import('../../shared/api/client.ts')>()),
  apiFetch: vi.fn(),
}))
const apiFetchMock = vi.mocked(apiFetch)

afterEach(() => {
  cleanup()
  vi.resetAllMocks()
})

const up: HostOverview = {
  id: 'a',
  name: 'Local',
  connectionType: 'LOCAL',
  endpoint: 'unix:///var/run/docker.sock',
  status: 'UP',
  error: null,
  dockerVersion: '27.1.2',
  cpus: 8,
  memoryBytes: 16 * 1024 ** 3,
  containers: { running: 5, stopped: 2, healthy: 3, unhealthy: 1 },
  images: 12,
  volumes: 4,
}
const down: HostOverview = {
  id: 'b',
  name: 'Staging',
  connectionType: 'TCP',
  endpoint: 'tcp://staging.example:2376',
  status: 'DOWN',
  error: 'TIMEOUT',
  dockerVersion: null,
  cpus: null,
  memoryBytes: null,
  containers: null,
  images: null,
  volumes: null,
}

function renderOverview(role: 'ADMIN' | 'OPERATOR' | 'VIEWER', rows: HostOverview[] | Error) {
  if (rows instanceof Error) apiFetchMock.mockRejectedValue(rows)
  else apiFetchMock.mockResolvedValue(rows)
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <EnvironmentsOverview role={role} />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('EnvironmentsOverview', () => {
  it('renders an UP row with formatted numbers and a link to the environment', async () => {
    renderOverview('VIEWER', [up])
    const link = await screen.findByRole('link', { name: /Local/ })
    expect(link).toHaveAttribute('href', '/environments/a')
    const row = link.closest('li')!
    expect(within(row).getByText('Up')).toBeInTheDocument()
    expect(within(row).getByText('Local')).toBeInTheDocument()
    expect(within(row).getByText('unix:///var/run/docker.sock')).toBeInTheDocument()
    expect(within(row).getByText('27.1.2')).toBeInTheDocument()
    expect(within(row).getByText('16 GiB')).toBeInTheDocument()
    expect(within(row).getByText('8 CPUs')).toBeInTheDocument()
    expect(within(row).getByText('5 running')).toBeInTheDocument()
    expect(within(row).getByText('2 stopped')).toBeInTheDocument()
    expect(within(row).getByText('3 healthy')).toBeInTheDocument()
    expect(within(row).getByText('1 unhealthy')).toBeInTheDocument()
    expect(within(row).getByText('12 images')).toBeInTheDocument()
    expect(within(row).getByText('4 volumes')).toBeInTheDocument()
  })

  it('renders a DOWN row with a human error and no stats', async () => {
    renderOverview('VIEWER', [down])
    const row = (await screen.findByRole('link', { name: /Staging/ })).closest('li')!
    expect(within(row).getByText('Down')).toBeInTheDocument()
    expect(within(row).getByText('TCP + TLS')).toBeInTheDocument()
    expect(within(row).getByText(/timed out/i)).toBeInTheDocument()
    expect(within(row).queryByText(/running/)).not.toBeInTheDocument()
    expect(within(row).queryByText(/images/)).not.toBeInTheDocument()
  })

  it('shows the add button only for ADMIN', async () => {
    const { unmount } = renderOverview('ADMIN', [up])
    expect(await screen.findByRole('button', { name: 'Add environment' })).toBeInTheDocument()
    unmount()
    renderOverview('OPERATOR', [up])
    await screen.findByRole('link', { name: /Local/ })
    expect(screen.queryByRole('button', { name: 'Add environment' })).not.toBeInTheDocument()
  })

  it('opens the environment form in a dialog and closes it with Escape', async () => {
    renderOverview('ADMIN', [up])
    fireEvent.click(await screen.findByRole('button', { name: 'Add environment' }))
    const dialog = screen.getByRole('dialog', { name: 'Add environment' })
    expect(within(dialog).getByRole('radio', { name: /local/i })).toBeInTheDocument()
    fireEvent.keyDown(dialog, { key: 'Escape' })
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
  })

  it('empty state: admin can add, others are pointed to an admin', async () => {
    const { unmount } = renderOverview('ADMIN', [])
    expect(await screen.findByText(/no environments yet/i)).toBeInTheDocument()
    expect(screen.getAllByRole('button', { name: 'Add environment' }).length).toBeGreaterThan(0)
    unmount()
    renderOverview('VIEWER', [])
    expect(await screen.findByText('No environments yet, ask an admin.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Add environment' })).not.toBeInTheDocument()
  })

  it('filters by name and shows a no-match message', async () => {
    renderOverview('VIEWER', [up, down])
    await screen.findByRole('link', { name: /Local/ })
    fireEvent.change(screen.getByRole('searchbox', { name: 'Search environments' }), {
      target: { value: 'stag' },
    })
    expect(screen.queryByRole('link', { name: /Local/ })).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Staging/ })).toBeInTheDocument()
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'zzz' } })
    expect(screen.getByText(/no environment matches/i)).toBeInTheDocument()
  })

  it('shows a loading skeleton first', () => {
    apiFetchMock.mockReturnValue(new Promise(() => {}))
    renderOverview('VIEWER', [])
    expect(screen.getByRole('status', { name: /loading/i })).toBeInTheDocument()
  })

  it('shows an error state and refresh refetches', async () => {
    renderOverview('VIEWER', new Error('boom'))
    expect(await screen.findByRole('alert')).toHaveTextContent(/could not load/i)
    apiFetchMock.mockResolvedValue([up])
    fireEvent.click(screen.getByRole('button', { name: 'Refresh' }))
    await waitFor(() => expect(screen.getByRole('link', { name: /Local/ })).toBeInTheDocument())
    expect(apiFetchMock).toHaveBeenCalledWith('/hosts/overview')
  })
})
