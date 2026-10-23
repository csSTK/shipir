import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, apiFetch } from '../../shared/api/client.ts'
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

const env: HostOverview = {
  id: 'a',
  name: 'Local',
  connectionType: 'LOCAL',
  endpoint: 'unix:///var/run/docker.sock',
  status: 'DOWN',
  error: 'TIMEOUT',
  dockerVersion: null,
  cpus: null,
  memoryBytes: null,
  containers: null,
  images: null,
  volumes: null,
}

function renderOverview(role: 'ADMIN' | 'OPERATOR' | 'VIEWER', mutation?: (path: string, method?: string) => unknown) {
  apiFetchMock.mockImplementation((path: string, options?: { method?: string }) => {
    if (path === '/hosts/overview') return Promise.resolve([env])
    return Promise.resolve(mutation ? mutation(path, options?.method) : undefined)
  })
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <EnvironmentsOverview role={role} />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

const mutationCalls = () => apiFetchMock.mock.calls.filter(([path]) => path !== '/hosts/overview')

describe('environment row actions', () => {
  it.each(['OPERATOR', 'VIEWER'] as const)('are hidden for %s', async (role) => {
    renderOverview(role)
    await screen.findByText('Local')
    expect(screen.queryByRole('button', { name: /rename/i })).toBeNull()
    expect(screen.queryByRole('button', { name: /delete/i })).toBeNull()
  })

  it('rename PATCHes the trimmed name and closes the dialog', async () => {
    renderOverview('ADMIN')
    fireEvent.click(await screen.findByRole('button', { name: 'Rename Local' }))
    const dialog = screen.getByRole('dialog', { name: 'Rename environment' })
    const input = within(dialog).getByLabelText('Name')
    expect(input).toHaveValue('Local')
    fireEvent.change(input, { target: { value: '  Production  ' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(mutationCalls()).toEqual([['/hosts/a', { method: 'PATCH', body: { name: 'Production' } }]])
  })

  it('rename cancel sends nothing', async () => {
    renderOverview('ADMIN')
    fireEvent.click(await screen.findByRole('button', { name: 'Rename Local' }))
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }))
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(mutationCalls()).toEqual([])
  })

  it('rename rejects an empty name without calling the API', async () => {
    renderOverview('ADMIN')
    fireEvent.click(await screen.findByRole('button', { name: 'Rename Local' }))
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: '   ' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    expect(await screen.findByRole('alert')).toHaveTextContent(/1 to 100/i)
    expect(mutationCalls()).toEqual([])
  })

  it('shows HOST_NAME_TAKEN inline and keeps the dialog open', async () => {
    renderOverview('ADMIN', () => Promise.reject(new ApiError(409, '', 'HOST_NAME_TAKEN')))
    fireEvent.click(await screen.findByRole('button', { name: 'Rename Local' }))
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Taken' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    expect(await screen.findByRole('alert')).toHaveTextContent(/name is already in use/i)
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })

  it('delete names the environment, warns about stacks and DELETEs on confirm', async () => {
    renderOverview('ADMIN')
    fireEvent.click(await screen.findByRole('button', { name: 'Delete Local' }))
    const dialog = screen.getByRole('dialog', { name: 'Delete environment' })
    expect(dialog).toHaveTextContent('Local')
    expect(dialog).toHaveTextContent(/stacks/i)
    expect(mutationCalls()).toEqual([])
    fireEvent.click(within(dialog).getByRole('button', { name: 'Delete' }))

    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(mutationCalls()).toEqual([['/hosts/a', { method: 'DELETE' }]])
  })

  it('delete cancel and Escape do nothing', async () => {
    renderOverview('ADMIN')
    fireEvent.click(await screen.findByRole('button', { name: 'Delete Local' }))
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }))
    expect(screen.queryByRole('dialog')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Delete Local' }))
    fireEvent.keyDown(screen.getByRole('dialog'), { key: 'Escape' })
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(mutationCalls()).toEqual([])
  })

  it('delete failure (404) is shown in the dialog', async () => {
    renderOverview('ADMIN', () => Promise.reject(new ApiError(404, '', 'HOST_NOT_FOUND')))
    fireEvent.click(await screen.findByRole('button', { name: 'Delete Local' }))
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Delete' }))
    expect(await screen.findByRole('alert')).toHaveTextContent(/no longer exists/i)
  })
})
