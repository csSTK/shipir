import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../../shared/api/client.ts'
import { ServicesCard } from './ServicesCard.tsx'

const apiFetch = vi.fn()
vi.mock('../../../shared/api/client.ts', async (importActual) => ({
  ...(await importActual<typeof import('../../../shared/api/client.ts')>()),
  apiFetch: (...args: unknown[]) => apiFetch(...args),
}))

afterEach(() => {
  cleanup()
  apiFetch.mockReset()
})

const BASE = '/hosts/h1/managed-deployments/s1'

function renderCard(opts: { canOperate?: boolean; profiles?: string[]; services?: unknown; onRun?: (kind: string, runId: string) => void } = {}) {
  const { canOperate = true, profiles = [], services = { services: ['db', 'web'], profiles: ['debug', 'tools'] }, onRun = vi.fn() } = opts
  apiFetch.mockImplementation((path: string, init?: { method?: string }) => {
    if (path === `${BASE}/services` && !init?.method) return services instanceof Error ? Promise.reject(services) : Promise.resolve(services)
    return Promise.resolve({ runId: 'run-1', versionId: 'v1' })
  })
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <ServicesCard hostId="h1" deploymentId="s1" activeProfiles={profiles} canOperate={canOperate} onRunStarted={onRun} />
    </QueryClientProvider>,
  )
  return onRun
}

describe('ServicesCard', () => {
  it('lists the services and renders nothing and fetches nothing for viewers', async () => {
    renderCard()
    expect(await screen.findByText('web')).toBeInTheDocument()
    expect(screen.getByText('db')).toBeInTheDocument()
    cleanup()
    apiFetch.mockReset()
    renderCard({ canOperate: false })
    expect(screen.queryByRole('region')).toBeNull()
    expect(apiFetch).not.toHaveBeenCalled()
  })

  it('hides itself on 403', async () => {
    renderCard({ services: new ApiError(403, 'no') })
    await waitFor(() => expect(apiFetch).toHaveBeenCalled())
    await waitFor(() => expect(screen.queryByRole('region')).toBeNull())
  })

  it('restarts a service and reports the run', async () => {
    const onRun = renderCard()
    fireEvent.click(await screen.findByRole('button', { name: 'Restart web' }))
    await waitFor(() => expect(apiFetch).toHaveBeenCalledWith(`${BASE}/services/web/restart`, { method: 'POST' }))
    await waitFor(() => expect(onRun).toHaveBeenCalledWith('Restart web', 'run-1'))
  })

  it('pulls a service', async () => {
    const onRun = renderCard()
    fireEvent.click(await screen.findByRole('button', { name: 'Pull image of db' }))
    await waitFor(() => expect(apiFetch).toHaveBeenCalledWith(`${BASE}/services/db/pull`, { method: 'POST' }))
    await waitFor(() => expect(onRun).toHaveBeenCalledWith('Pull db', 'run-1'))
  })

  it('brings a service up from the overflow menu', async () => {
    renderCard()
    fireEvent.pointerDown(await screen.findByRole('button', { name: 'More actions for web' }), { button: 0, ctrlKey: false })
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Up' }))
    await waitFor(() => expect(apiFetch).toHaveBeenCalledWith(`${BASE}/services/web/up`, { method: 'POST' }))
  })

  it('starts all services', async () => {
    renderCard()
    fireEvent.click(await screen.findByRole('button', { name: 'Start all' }))
    await waitFor(() => expect(apiFetch).toHaveBeenCalledWith(`${BASE}/start`, { method: 'POST' }))
  })

  it('maps 409 busy to a message and does not report a run', async () => {
    const onRun = renderCard()
    apiFetch.mockImplementation((_path: string, init?: { method?: string }) =>
      init?.method === 'POST'
        ? Promise.reject(new ApiError(409, 'busy', 'DEPLOYMENT_IN_PROGRESS'))
        : Promise.resolve({ services: ['web'], profiles: [] }),
    )
    fireEvent.click(await screen.findByRole('button', { name: 'Restart web' }))
    expect(await screen.findByRole('alert')).toHaveTextContent(/already in progress/i)
    expect(onRun).not.toHaveBeenCalled()
  })

  it('saves the selected profiles', async () => {
    renderCard({ profiles: ['debug'] })
    const debug = await screen.findByRole('checkbox', { name: 'debug' })
    const tools = screen.getByRole('checkbox', { name: 'tools' })
    expect(debug).toBeChecked()
    expect(tools).not.toBeChecked()
    const save = screen.getByRole('button', { name: 'Save profiles' })
    expect(save).toBeDisabled()
    fireEvent.click(tools)
    fireEvent.click(debug)
    fireEvent.click(save)
    await waitFor(() => expect(apiFetch).toHaveBeenCalledWith(`${BASE}/profiles`, { method: 'PUT', body: { profiles: ['tools'] } }))
  })

  it('shows a hint when the file defines no profiles', async () => {
    renderCard({ services: { services: ['web'], profiles: [] } })
    expect(await screen.findByText(/no profiles/i)).toBeInTheDocument()
  })

  it('shows an error with retry when loading fails', async () => {
    renderCard({ services: new ApiError(500, 'boom') })
    expect(await screen.findByRole('alert')).toHaveTextContent(/could not load/i)
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument()
  })
})
