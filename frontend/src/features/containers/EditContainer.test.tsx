import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../shared/api/client.ts'
import { ContainerDetailsPage } from './details/ContainerDetailsPage.tsx'
import { ContainerResourcesDialog } from './ContainerResourcesDialog.tsx'
import { CreateContainerDialog } from './CreateContainerDialog.tsx'
import { sample } from './containerFixture.ts'

let role = 'OPERATOR'
const apiFetch = vi.fn()
vi.mock('../../shared/api/client.ts', async (importActual) => ({
  ...(await importActual<typeof import('../../shared/api/client.ts')>()),
  apiFetch: (...args: unknown[]) => apiFetch(...args),
}))
vi.mock('../../shared/realtime/stomp.ts', () => ({ subscribeToTopic: () => () => {} }))

afterEach(() => {
  cleanup()
  apiFetch.mockReset()
})

const call = (method: string) => apiFetch.mock.calls.find((c) => (c[1] as { method?: string } | undefined)?.method === method)
const wrap = (ui: React.ReactNode) => render(<QueryClientProvider client={new QueryClient()}>{ui}</QueryClientProvider>)

describe('CreateContainerDialog in edit mode', () => {
  it('prefills from inspect, asks for confirmation and only then recreates with the old name', async () => {
    apiFetch.mockResolvedValue({ id: 'c2', name: 'web' })
    const onDone = vi.fn()
    wrap(<CreateContainerDialog hostId="h1" isAdmin mode="edit" source={sample} onClose={() => {}} onDone={onDone} />)
    expect(screen.getByRole('heading', { name: 'Edit container' })).toBeInTheDocument()
    expect(screen.getByLabelText('Image')).toHaveValue('nginx:1')
    expect(screen.getByLabelText('Name')).toBeDisabled()
    expect(screen.getByLabelText('Command (optional)')).toHaveValue('nginx -g "daemon off;"')
    expect(screen.queryByText(/Leave this empty to keep/)).toBeNull()
    fireEvent.change(screen.getByLabelText('Image'), { target: { value: 'nginx:2' } })
    fireEvent.click(screen.getByRole('button', { name: 'Review changes' }))
    expect(screen.getByText(/will be recreated/)).toBeInTheDocument()
    expect(call('POST')).toBeUndefined()
    fireEvent.click(screen.getByRole('button', { name: 'Recreate container' }))
    await waitFor(() => expect(onDone).toHaveBeenCalledWith('c2'))
    expect(call('POST')![0]).toBe('/hosts/h1/containers/c1/recreate')
    const body = (call('POST')![1] as { body: Record<string, unknown> }).body
    expect(body).toMatchObject({ image: 'nginx:2', name: 'web', start: true, network: 'shop_net' })
    expect(body.command).toEqual(['nginx', '-g', 'daemon off;'])
    expect(body.mounts).toHaveLength(2)
  })

  it('shows a failed recreate inline and keeps the dialog open', async () => {
    apiFetch.mockRejectedValue(new ApiError(409, 'x', 'CONTAINER_RECREATE_FAILED'))
    const onClose = vi.fn()
    wrap(<CreateContainerDialog hostId="h1" isAdmin={false} mode="edit" source={sample} onClose={onClose} />)
    fireEvent.click(screen.getByRole('button', { name: 'Review changes' }))
    fireEvent.click(screen.getByRole('button', { name: 'Recreate container' }))
    expect(await screen.findByText(/original container was restored/)).toBeInTheDocument()
    expect(onClose).not.toHaveBeenCalled()
  })
})

describe('CreateContainerDialog unchanged edit', () => {
  it('posts exactly the original cmd, even with quotes, backslashes and empty arguments', async () => {
    apiFetch.mockResolvedValue({ id: 'c2', name: 'web' })
    const cmd = ['sh', '-c', 'echo "hi" && echo \'x\' \\', '']
    wrap(<CreateContainerDialog hostId="h1" isAdmin mode="edit" source={{ ...sample, cmd }} onClose={() => {}} />)
    fireEvent.click(screen.getByRole('button', { name: 'Review changes' }))
    fireEvent.click(screen.getByRole('button', { name: 'Recreate container' }))
    await waitFor(() => expect(call('POST')).toBeDefined())
    expect((call('POST')![1] as { body: { command: string[] } }).body.command).toEqual(cmd)
  })
})

describe('CreateContainerDialog edit mode keeps privileged and host network', () => {
  it('shows the prefilled flags to admins and sends them', async () => {
    apiFetch.mockResolvedValue({ id: 'c2', name: 'web' })
    wrap(<CreateContainerDialog hostId="h1" isAdmin mode="edit" source={{ ...sample, privileged: true, hostNetwork: true, ports: [], networks: [] }} onClose={() => {}} />)
    expect(screen.getByLabelText(/Privileged/)).toBeChecked()
    expect(screen.getByLabelText(/host network/)).toBeChecked()
    fireEvent.click(screen.getByRole('button', { name: 'Review changes' }))
    fireEvent.click(screen.getByRole('button', { name: 'Recreate container' }))
    await waitFor(() => expect(call('POST')).toBeDefined())
    expect((call('POST')![1] as { body: unknown }).body).toMatchObject({ privileged: true, hostNetwork: true })
  })

  it('warns that only one network is kept when the container has several', () => {
    const two = [sample.networks[0], { ...sample.networks[0], name: 'other_net' }]
    wrap(<CreateContainerDialog hostId="h1" isAdmin={false} mode="edit" source={{ ...sample, networks: two }} onClose={() => {}} />)
    expect(screen.getByText(/connected to 2 networks/)).toBeInTheDocument()
  })
})

describe('CreateContainerDialog in duplicate mode', () => {
  it('starts with an empty name and no host ports and posts to duplicate', async () => {
    apiFetch.mockResolvedValue({ id: 'c3', name: 'web-2' })
    const onDone = vi.fn()
    wrap(<CreateContainerDialog hostId="h1" isAdmin={false} mode="duplicate" source={sample} onClose={() => {}} onDone={onDone} />)
    expect(screen.getByLabelText('Name')).toHaveValue('')
    expect(screen.getByLabelText('Port 1 host port')).toHaveValue('')
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'web-2' } })
    fireEvent.click(screen.getByRole('button', { name: 'Duplicate' }))
    await waitFor(() => expect(onDone).toHaveBeenCalledWith('c3'))
    expect(call('POST')![0]).toBe('/hosts/h1/containers/c1/duplicate')
    expect((call('POST')![1] as { body: { name: string } }).body.name).toBe('web-2')
  })

  it('requires a name', () => {
    wrap(<CreateContainerDialog hostId="h1" isAdmin={false} mode="duplicate" source={sample} onClose={() => {}} />)
    fireEvent.click(screen.getByRole('button', { name: 'Duplicate' }))
    expect(screen.getByText('Enter a name for the copy.')).toBeInTheDocument()
    expect(call('POST')).toBeUndefined()
  })
})

describe('ContainerResourcesDialog', () => {
  it('sends memory in bytes, cpus and a changed restart policy, 0 meaning unlimited', async () => {
    apiFetch.mockResolvedValue(undefined)
    const onClose = vi.fn()
    wrap(<ContainerResourcesDialog hostId="h1" container={sample} onClose={onClose} />)
    fireEvent.change(screen.getByLabelText('Memory limit (MiB)'), { target: { value: '512' } })
    fireEvent.change(screen.getByLabelText('CPUs'), { target: { value: '0' } })
    fireEvent.change(screen.getByLabelText('Restart policy'), { target: { value: 'always' } })
    fireEvent.click(screen.getByRole('button', { name: 'Apply limits' }))
    await waitFor(() => expect(onClose).toHaveBeenCalled())
    expect(call('PATCH')![0]).toBe('/hosts/h1/containers/c1/resources')
    expect((call('PATCH')![1] as { body: unknown }).body).toEqual({ memoryBytes: 512 * 1024 * 1024, cpus: 0, restartPolicy: 'always' })
  })

  it('rejects memory below 6 MiB and an empty change without a request', () => {
    wrap(<ContainerResourcesDialog hostId="h1" container={sample} onClose={() => {}} />)
    fireEvent.click(screen.getByRole('button', { name: 'Apply limits' }))
    expect(screen.getByText('Change at least one value.')).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Memory limit (MiB)'), { target: { value: '3' } })
    fireEvent.click(screen.getByRole('button', { name: 'Apply limits' }))
    expect(screen.getByText('Use 0 (unlimited) or at least 6 MiB.')).toBeInTheDocument()
    expect(call('PATCH')).toBeUndefined()
  })
})

function renderDetails(details: typeof sample) {
  apiFetch.mockImplementation((path: string) => {
    if (path === '/auth/me') return Promise.resolve({ username: 'u', role, setupRequired: false })
    return Promise.resolve(details)
  })
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter initialEntries={['/environments/h1/containers/c1']}>
        <Routes>
          <Route path="/environments/:id/containers/:cid" element={<ContainerDetailsPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('ContainerDetailsPage edit actions', () => {
  it('offers Edit, Duplicate and Limits to operators', async () => {
    role = 'OPERATOR'
    renderDetails(sample)
    expect(await screen.findByRole('button', { name: 'Edit' })).toBeEnabled()
    expect(screen.getByRole('button', { name: 'Duplicate' })).toBeEnabled()
    fireEvent.click(screen.getByRole('button', { name: 'Resource limits' }))
    expect(await screen.findByRole('button', { name: 'Apply limits' })).toBeInTheDocument()
  })

  it('disables the actions for operators when the container is privileged or uses the host network', async () => {
    role = 'OPERATOR'
    renderDetails({ ...sample, privileged: true })
    expect(await screen.findByRole('button', { name: 'Edit' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Duplicate' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Resource limits' })).toBeEnabled()
    expect(screen.getByText(/needs an administrator/)).toBeInTheDocument()
  })

  it('hides the actions from viewers', async () => {
    role = 'VIEWER'
    renderDetails(sample)
    await screen.findByRole('heading', { name: 'web', level: 1 })
    expect(screen.queryByRole('button', { name: 'Edit' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Duplicate' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Resource limits' })).toBeNull()
  })

  it('disables them with a reason for compose-managed containers', async () => {
    role = 'ADMIN'
    renderDetails({ ...sample, composeProject: 'shop', composeService: 'web' })
    expect(await screen.findByRole('button', { name: 'Edit' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Duplicate' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Resource limits' })).toBeDisabled()
    expect(screen.getByText(/managed by the deployment shop/)).toBeInTheDocument()
  })
})
