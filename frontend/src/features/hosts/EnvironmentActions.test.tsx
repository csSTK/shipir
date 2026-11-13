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
  tags: ['prod'],
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

async function choose(item: 'Rename' | 'Delete' | 'Edit tags') {
  const trigger = await screen.findByRole('button', { name: 'Actions for Local' })
  trigger.focus()
  fireEvent.keyDown(trigger, { key: 'Enter' })
  fireEvent.click(await screen.findByRole('menuitem', { name: item }))
}

const gone = () => waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())

const mutationCalls = () => apiFetchMock.mock.calls.filter(([path]) => path !== '/hosts/overview' && path !== '/hosts/a/proxy/managed')

describe('environment row actions', () => {
  it.each(['OPERATOR', 'VIEWER'] as const)('are hidden for %s', async (role) => {
    renderOverview(role)
    await screen.findByText('Local')
    expect(screen.queryByRole('button', { name: /actions for/i })).toBeNull()
  })

  it('rename PATCHes the trimmed name and closes the dialog', async () => {
    renderOverview('ADMIN')
    await choose('Rename')
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
    await choose('Rename')
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }))
    await gone()
    expect(mutationCalls()).toEqual([])
  })

  it('rename rejects an empty name without calling the API', async () => {
    renderOverview('ADMIN')
    await choose('Rename')
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: '   ' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    expect(await screen.findByRole('alert')).toHaveTextContent(/1 to 100/i)
    expect(mutationCalls()).toEqual([])
  })

  it('rename error sits outside the label and is linked to the input', async () => {
    renderOverview('ADMIN')
    await choose('Rename')
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: '   ' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    const alert = await screen.findByRole('alert')
    const input = screen.getByLabelText('Name')
    expect(alert.closest('label')).toBeNull()
    expect(input).toHaveAttribute('aria-invalid', 'true')
    expect(input.getAttribute('aria-describedby')).toContain(alert.id)
  })

  it('shows HOST_NAME_TAKEN inline and keeps the dialog open', async () => {
    renderOverview('ADMIN', () => Promise.reject(new ApiError(409, '', 'HOST_NAME_TAKEN')))
    await choose('Rename')
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Taken' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    expect(await screen.findByRole('alert')).toHaveTextContent(/name is already in use/i)
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })

  it('delete names the environment, warns about deployments and DELETEs on confirm', async () => {
    renderOverview('ADMIN')
    await choose('Delete')
    const dialog = screen.getByRole('dialog', { name: 'Delete environment' })
    expect(dialog).toHaveTextContent('Local')
    expect(dialog).toHaveTextContent(/deployments/i)
    expect(mutationCalls()).toEqual([])
    fireEvent.click(within(dialog).getByRole('button', { name: 'Delete' }))

    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(mutationCalls()).toEqual([['/hosts/a', { method: 'DELETE' }]])
  })

  const withProxy = (path: string, method?: string) => {
    if (path === '/hosts/a/proxy/managed') return { instance: { type: 'TRAEFIK' }, setupBlockedReason: null }
    return method === 'DELETE' ? { leftovers: [] } : undefined
  }

  it('shows the remove-proxy checkbox only when the host has a managed proxy', async () => {
    renderOverview('ADMIN')
    await choose('Delete')
    const dialog = screen.getByRole('dialog', { name: 'Delete environment' })
    await waitFor(() => expect(apiFetchMock).toHaveBeenCalledWith('/hosts/a/proxy/managed'))
    expect(within(dialog).queryByRole('checkbox')).toBeNull()
  })

  it('the checkbox is off by default and an unchecked delete does not ask for proxy removal', async () => {
    renderOverview('ADMIN', withProxy)
    await choose('Delete')
    const dialog = screen.getByRole('dialog', { name: 'Delete environment' })
    const box = await within(dialog).findByRole('checkbox', { name: /also remove the managed proxy/i })
    expect(box).not.toBeChecked()
    fireEvent.click(within(dialog).getByRole('button', { name: 'Delete' }))
    await gone()
    expect(mutationCalls()).toEqual([['/hosts/a', { method: 'DELETE' }]])
  })

  it('a checked box sends removeProxy=true', async () => {
    renderOverview('ADMIN', withProxy)
    await choose('Delete')
    const dialog = screen.getByRole('dialog', { name: 'Delete environment' })
    fireEvent.click(await within(dialog).findByRole('checkbox', { name: /also remove the managed proxy/i }))
    fireEvent.click(within(dialog).getByRole('button', { name: 'Delete' }))
    await gone()
    expect(mutationCalls()).toEqual([['/hosts/a?removeProxy=true', { method: 'DELETE' }]])
  })

  it('lists what the proxy removal left behind and keeps the dialog until it is closed', async () => {
    renderOverview('ADMIN', (path, method) =>
      method === 'DELETE' ? { leftovers: ['volume shipir-proxy_shipir-proxy-acme', 'network shipir-proxy'] } : withProxy(path, method),
    )
    await choose('Delete')
    const dialog = screen.getByRole('dialog', { name: 'Delete environment' })
    fireEvent.click(await within(dialog).findByRole('checkbox', { name: /also remove the managed proxy/i }))
    fireEvent.click(within(dialog).getByRole('button', { name: 'Delete' }))

    const result = await screen.findByRole('dialog', { name: 'Environment deleted' })
    expect(result).toHaveTextContent('volume shipir-proxy_shipir-proxy-acme')
    expect(result).toHaveTextContent('network shipir-proxy')
    fireEvent.click(within(result).getByRole('button', { name: 'Done' }))
    await gone()
  })

  it('delete cancel and Escape do nothing', async () => {
    renderOverview('ADMIN')
    await choose('Delete')
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }))
    await gone()
    await choose('Delete')
    fireEvent.keyDown(screen.getByRole('dialog'), { key: 'Escape' })
    await gone()
    expect(mutationCalls()).toEqual([])
  })

  it('delete failure (404) is shown in the dialog', async () => {
    renderOverview('ADMIN', () => Promise.reject(new ApiError(404, '', 'HOST_NOT_FOUND')))
    await choose('Delete')
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Delete' }))
    expect(await screen.findByRole('alert')).toHaveTextContent(/no longer exists/i)
  })

  it('opens the edit tags dialog for ADMIN', async () => {
    renderOverview('ADMIN')
    await choose('Edit tags')
    expect(screen.getByRole('dialog', { name: 'Edit tags' })).toBeInTheDocument()
  })

  it('edit tags PUTs the full replacement set and closes the dialog', async () => {
    renderOverview('ADMIN')
    await choose('Edit tags')
    const dialog = screen.getByRole('dialog', { name: 'Edit tags' })
    expect(within(dialog).getByText('prod')).toBeInTheDocument()
    const input = within(dialog).getByRole('textbox', { name: 'Tags' })
    fireEvent.change(input, { target: { value: 'EU' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(mutationCalls()).toEqual([['/hosts/a/tags', { method: 'PUT', body: { tags: ['prod', 'eu'] } }]])
  })

  it('edit tags shows a server error inline and keeps the dialog open', async () => {
    renderOverview('ADMIN', (path) => (path === '/hosts/a/tags' ? Promise.reject(new ApiError(400, '', 'TOO_MANY_TAGS')) : undefined))
    await choose('Edit tags')
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    expect(await screen.findByRole('alert')).toHaveTextContent(/at most 10/i)
    expect(screen.getByRole('dialog', { name: 'Edit tags' })).toBeInTheDocument()
  })
})
