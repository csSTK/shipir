import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../../shared/api/client.ts'
import { UptimeCard } from './UptimeCard.tsx'
import type { UptimeCheck } from './api.ts'

const apiFetch = vi.fn()
vi.mock('../../../shared/api/client.ts', async (importActual) => ({
  ...(await importActual<typeof import('../../../shared/api/client.ts')>()),
  apiFetch: (...args: unknown[]) => apiFetch(...args),
}))

afterEach(() => {
  cleanup()
  apiFetch.mockReset()
})

const PATH = '/hosts/h1/managed-deployments/s1/uptime-checks'
const check: UptimeCheck = {
  id: 'c1',
  name: 'Homepage',
  url: 'https://user:pw@example.com/health?token=SECRET',
  expectedStatus: 200,
  intervalSeconds: 60,
  timeoutSeconds: 10,
  enabled: true,
  lastStatus: 'DOWN',
  lastCheckedAt: '2026-10-25T10:00:00Z',
  lastChangeAt: '2026-10-25T09:00:00Z',
  consecutiveFailures: 3,
  recentResults: [
    { checkedAt: '2026-10-25T10:00:00Z', ok: false, statusCode: 503, latencyMs: 20, errorCode: null },
    { checkedAt: '2026-10-25T09:59:00Z', ok: true, statusCode: 200, latencyMs: 20, errorCode: null },
  ],
}

function renderCard(list: UptimeCheck[] | Error, canOperate = true) {
  apiFetch.mockImplementation((path: string, init?: { method?: string }) => {
    if (path === PATH && !init?.method) return list instanceof Error ? Promise.reject(list) : Promise.resolve(list)
    return Promise.resolve(undefined)
  })
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <UptimeCard hostId="h1" deploymentId="s1" canOperate={canOperate} />
    </QueryClientProvider>,
  )
}

describe('UptimeCard', () => {
  it('lists a check with state text, host path only and last status code', async () => {
    renderCard([check])
    expect(await screen.findByText('Homepage')).toBeInTheDocument()
    expect(screen.getByText('Down')).toBeInTheDocument()
    expect(screen.getByText(/^example\.com\/health · every 60 s/)).toBeInTheDocument()
    expect(screen.queryByText(/SECRET/)).toBeNull()
    expect(screen.queryByText(/user:pw/)).toBeNull()
    expect(screen.getByText(/503/)).toBeInTheDocument()
  })

  it('renders nothing and fetches nothing for viewers', () => {
    renderCard([check], false)
    expect(screen.queryByRole('region')).toBeNull()
    expect(apiFetch).not.toHaveBeenCalled()
  })

  it('shows an empty state', async () => {
    renderCard([])
    expect(await screen.findByText(/no uptime checks/i)).toBeInTheDocument()
  })

  it('shows an error with retry', async () => {
    renderCard(new ApiError(500, 'boom'))
    expect(await screen.findByRole('alert')).toHaveTextContent(/could not load/i)
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument()
  })

  it('creates a check with the form values', async () => {
    renderCard([])
    fireEvent.click(await screen.findByRole('button', { name: 'Add check' }))
    const dialog = await screen.findByRole('dialog')
    fireEvent.change(within(dialog).getByLabelText('Name'), { target: { value: 'API' } })
    fireEvent.change(within(dialog).getByLabelText('URL'), { target: { value: 'https://example.com/ping' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save' }))
    await waitFor(() =>
      expect(apiFetch).toHaveBeenCalledWith(PATH, {
        method: 'POST',
        body: { name: 'API', url: 'https://example.com/ping', expectedStatus: 200, intervalSeconds: 60, timeoutSeconds: 10, enabled: true },
      }),
    )
  })

  it('validates the URL and interval client side without calling the API', async () => {
    renderCard([])
    fireEvent.click(await screen.findByRole('button', { name: 'Add check' }))
    const dialog = await screen.findByRole('dialog')
    fireEvent.change(within(dialog).getByLabelText('Name'), { target: { value: 'API' } })
    fireEvent.change(within(dialog).getByLabelText('URL'), { target: { value: 'ftp://u:p@example.com' } })
    fireEvent.change(within(dialog).getByLabelText('Interval (seconds)'), { target: { value: '5' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save' }))
    expect(await within(dialog).findByText(/http or https/i)).toBeInTheDocument()
    expect(within(dialog).getByText(/between 30 and 86400/i)).toBeInTheDocument()
    expect(apiFetch.mock.calls.filter((c) => c[1]?.method === 'POST')).toHaveLength(0)
  })

  it('hints at the allow-private-ranges property next to an EGRESS_DENIED result', async () => {
    const denied: UptimeCheck = {
      ...check,
      recentResults: [{ checkedAt: '2026-10-25T10:00:00Z', ok: false, statusCode: null, latencyMs: null, errorCode: 'EGRESS_DENIED' }],
    }
    renderCard([denied])
    expect(await screen.findByText(/shipir\.egress\.allow-private-ranges/)).toBeInTheDocument()
  })

  it('shows no egress hint for other results', async () => {
    renderCard([check])
    await screen.findByText('Homepage')
    expect(screen.queryByText(/allow-private-ranges/)).not.toBeInTheDocument()
  })

  it('toggles enabled inline and deletes after confirmation', async () => {
    renderCard([check])
    fireEvent.click(await screen.findByRole('switch', { name: /Homepage/ }))
    await waitFor(() =>
      expect(apiFetch).toHaveBeenCalledWith(`${PATH}/c1`, expect.objectContaining({ method: 'PUT', body: expect.objectContaining({ enabled: false, url: check.url }) })),
    )
    fireEvent.click(screen.getByRole('button', { name: 'Delete Homepage' }))
    const dialog = await screen.findByRole('dialog')
    expect(apiFetch).not.toHaveBeenCalledWith(`${PATH}/c1`, { method: 'DELETE' })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Delete' }))
    await waitFor(() => expect(apiFetch).toHaveBeenCalledWith(`${PATH}/c1`, { method: 'DELETE' }))
  })
})
