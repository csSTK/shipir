import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../shared/api/client.ts'
import type { HistoryPoint, StatsHistory } from './api.ts'
import { ContainerUsageHistory, HostUsageHistory } from './UsageHistory.tsx'

const apiFetch = vi.fn()
vi.mock('../../shared/api/client.ts', async (importActual) => ({
  ...(await importActual<typeof import('../../shared/api/client.ts')>()),
  apiFetch: (...args: unknown[]) => apiFetch(...args),
}))
afterEach(() => {
  cleanup()
  apiFetch.mockReset()
})

const MIN = 60_000
const now = Date.now()
const point = (i: number, o: Partial<HistoryPoint> = {}): HistoryPoint => ({
  time: new Date(now - (10 - i) * MIN).toISOString(),
  cpuPercent: 10 + i,
  memoryBytes: 100 * 1024 ** 2,
  networkRxBytes: i * 6000,
  networkTxBytes: 0,
  blockReadBytes: 0,
  blockWriteBytes: 0,
  ...o,
})
const history = (series: StatsHistory['series'], extra: Partial<StatsHistory> = {}): StatsHistory => ({
  range: '24h',
  from: new Date(now - 24 * 60 * MIN).toISOString(),
  to: new Date(now).toISOString(),
  bucketSeconds: 60,
  seriesTotal: series.length,
  truncated: false,
  series,
  ...extra,
})
const one = history([{ containerId: 'c1', points: [point(0), point(1), point(2)] }])

function Location() {
  return <output data-testid="loc">{useLocation().search}</output>
}
function renderIt(ui: React.ReactElement, url = '/') {
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter initialEntries={[url]}>
        {ui}
        <Location />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('ContainerUsageHistory', () => {
  it('shows a loading skeleton, then charts for cpu, memory, network and disk', async () => {
    apiFetch.mockResolvedValue(one)
    renderIt(<ContainerUsageHistory hostId="h1" containerId="c1" />)
    expect(screen.getByRole('status', { name: /loading usage history/i })).toBeInTheDocument()
    expect(await screen.findByRole('img', { name: /CPU/ })).toBeInTheDocument()
    expect(screen.getByRole('img', { name: /Memory/ })).toBeInTheDocument()
    expect(screen.getByRole('img', { name: /Network/ })).toBeInTheDocument()
    expect(screen.getByRole('img', { name: /Disk/ })).toBeInTheDocument()
    expect(apiFetch).toHaveBeenCalledWith('/hosts/h1/stats/history?range=24h&container=c1')
  })

  it('keeps a cpu value above 100 percent inside the plot', async () => {
    apiFetch.mockResolvedValue(history([{ containerId: 'c1', points: [point(0, { cpuPercent: 40 }), point(1, { cpuPercent: 250 })] }]))
    renderIt(<ContainerUsageHistory hostId="h1" containerId="c1" />)
    const cpu = await screen.findByRole('img', { name: /CPU/ })
    const ys = [...cpu.querySelectorAll('path.tschart__line')].flatMap((p) => [...(p.getAttribute('d') ?? '').matchAll(/,(-?[\d.]+)/g)].map((m) => Number(m[1])))
    expect(ys.length).toBeGreaterThan(0)
    expect(Math.min(...ys)).toBeGreaterThanOrEqual(0)
  })

  it('refetches with the new range and keeps it in the url', async () => {
    apiFetch.mockResolvedValue(one)
    renderIt(<ContainerUsageHistory hostId="h1" containerId="c1" />)
    await screen.findByRole('img', { name: /CPU/ })
    fireEvent.change(screen.getByLabelText('Time range'), { target: { value: '7d' } })
    await waitFor(() => expect(apiFetch).toHaveBeenCalledWith('/hosts/h1/stats/history?range=7d&container=c1'))
    expect(screen.getByTestId('loc')).toHaveTextContent('usageRange=7d')
  })

  it('reads the range from the url and falls back for an unknown value', async () => {
    apiFetch.mockResolvedValue(one)
    renderIt(<ContainerUsageHistory hostId="h1" containerId="c1" />, '/?usageRange=6h')
    await screen.findByRole('img', { name: /CPU/ })
    expect(apiFetch).toHaveBeenCalledWith('/hosts/h1/stats/history?range=6h&container=c1')
    cleanup()
    apiFetch.mockClear()
    renderIt(<ContainerUsageHistory hostId="h1" containerId="c1" />, '/?usageRange=bogus')
    await screen.findByRole('img', { name: /CPU/ })
    expect(apiFetch).toHaveBeenCalledWith('/hosts/h1/stats/history?range=24h&container=c1')
  })

  it('shows the empty state when no samples exist', async () => {
    apiFetch.mockResolvedValue(history([]))
    renderIt(<ContainerUsageHistory hostId="h1" containerId="c1" />)
    expect(await screen.findByText(/No history yet: samples are recorded every minute/)).toBeInTheDocument()
    expect(screen.queryByRole('img')).not.toBeInTheDocument()
  })

  it('shows an error with a retry that loads again', async () => {
    apiFetch.mockRejectedValueOnce(new ApiError(500, 'boom', 'INTERNAL')).mockResolvedValue(one)
    renderIt(<ContainerUsageHistory hostId="h1" containerId="c1" />)
    expect(await screen.findByRole('alert')).toHaveTextContent(/could not load usage history/i)
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }))
    expect(await screen.findByRole('img', { name: /CPU/ })).toBeInTheDocument()
  })
})

describe('HostUsageHistory', () => {
  const two = history([
    { containerId: 'a', points: [point(0, { cpuPercent: 10 }), point(1, { cpuPercent: 20 })] },
    { containerId: 'b', points: [point(1, { cpuPercent: 5 })] },
  ])

  it('shows the aggregate cpu and memory charts of the host', async () => {
    apiFetch.mockResolvedValue(two)
    renderIt(<HostUsageHistory hostId="h1" />)
    expect(await screen.findByRole('img', { name: /CPU/ })).toBeInTheDocument()
    expect(screen.getByRole('img', { name: /Memory/ })).toBeInTheDocument()
    expect(apiFetch).toHaveBeenCalledWith('/hosts/h1/stats/history?range=24h')
    expect(screen.queryByText(/most recently active/)).not.toBeInTheDocument()
  })

  it('says clearly when the list of containers is truncated', async () => {
    apiFetch.mockResolvedValue({ ...two, truncated: true, seriesTotal: 80 })
    renderIt(<HostUsageHistory hostId="h1" />)
    expect(await screen.findByText('Showing the 2 most recently active containers')).toBeInTheDocument()
  })

  it('renders nothing when the host is not visible (404)', async () => {
    apiFetch.mockRejectedValue(new ApiError(404, 'nope', 'HOST_NOT_FOUND'))
    renderIt(<HostUsageHistory hostId="h1" />)
    await waitFor(() => expect(apiFetch).toHaveBeenCalled())
    await waitFor(() => expect(screen.queryByRole('status', { name: /loading usage history/i })).not.toBeInTheDocument())
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(screen.queryByText(/usage history/i)).not.toBeInTheDocument()
  })
})
