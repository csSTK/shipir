import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../shared/api/client.ts'
import { CreateNetworkDialog } from '../networks/CreateNetworkDialog.tsx'
import { CreateVolumeDialog } from '../volumes/CreateVolumeDialog.tsx'
import { CreateContainerDialog } from './CreateContainerDialog.tsx'

const apiFetch = vi.fn()
vi.mock('../../shared/api/client.ts', async (importActual) => ({
  ...(await importActual<typeof import('../../shared/api/client.ts')>()),
  apiFetch: (...args: unknown[]) => apiFetch(...args),
}))

afterEach(() => {
  cleanup()
  apiFetch.mockReset()
})

function wrap(ui: React.ReactNode) {
  return render(<QueryClientProvider client={new QueryClient()}>{ui}</QueryClientProvider>)
}
const post = () => apiFetch.mock.calls.find((c) => (c[1] as { method?: string } | undefined)?.method === 'POST')
const type = (label: string, value: string) => fireEvent.change(screen.getByLabelText(label), { target: { value } })

describe('CreateContainerDialog', () => {
  it('rejects a missing image and invalid port client-side without a request', () => {
    wrap(<CreateContainerDialog hostId="h1" isAdmin={false} onClose={() => {}} />)
    fireEvent.click(screen.getByRole('button', { name: 'Add port' }))
    type('Port 1 container port', '70000')
    fireEvent.click(screen.getByRole('button', { name: 'Create' }))
    expect(screen.getByText('Enter an image, for example nginx:1.27.')).toBeInTheDocument()
    expect(screen.getByText('Use 1-65535.')).toBeInTheDocument()
    expect(post()).toBeUndefined()
  })

  it('sends the spec and closes on success', async () => {
    apiFetch.mockResolvedValue({ id: 'c1', name: 'web' })
    const onClose = vi.fn()
    wrap(<CreateContainerDialog hostId="h1" isAdmin={false} onClose={onClose} />)
    type('Image', 'nginx:1.27')
    type('Name (optional)', 'web')
    type('Command (optional)', 'sleep "3 600"')
    fireEvent.click(screen.getByRole('button', { name: 'Add port' }))
    type('Port 1 container port', '80')
    fireEvent.click(screen.getByRole('button', { name: 'Create' }))
    await waitFor(() => expect(onClose).toHaveBeenCalled())
    expect(post()![0]).toBe('/hosts/h1/containers')
    expect((post()![1] as { body: unknown }).body).toEqual({
      image: 'nginx:1.27',
      name: 'web',
      command: ['sleep', '3 600'],
      ports: [{ hostPort: null, containerPort: 80, protocol: 'tcp' }],
      restartPolicy: 'no',
      start: true,
    })
  })

  it('hides ADMIN-only options for operators and shows them for admins', () => {
    const { unmount } = wrap(<CreateContainerDialog hostId="h1" isAdmin={false} onClose={() => {}} />)
    fireEvent.click(screen.getByRole('button', { name: 'Add mount' }))
    expect(screen.queryByLabelText(/Privileged/)).toBeNull()
    expect(screen.queryByLabelText(/host network/)).toBeNull()
    expect(screen.queryByLabelText('Mount 1 type')).toBeNull()
    unmount()
    wrap(<CreateContainerDialog hostId="h1" isAdmin onClose={() => {}} />)
    fireEvent.click(screen.getByRole('button', { name: 'Add mount' }))
    expect(screen.getByLabelText(/Privileged/)).toBeInTheDocument()
    expect(screen.getByLabelText(/host network/)).toBeInTheDocument()
    expect(screen.getByLabelText('Mount 1 type')).toBeInTheDocument()
  })

  it('shows server errors inline and stays open', async () => {
    apiFetch.mockRejectedValue(new ApiError(409, 'taken', 'NAME_TAKEN'))
    const onClose = vi.fn()
    wrap(<CreateContainerDialog hostId="h1" isAdmin={false} onClose={onClose} />)
    type('Image', 'nginx')
    fireEvent.click(screen.getByRole('button', { name: 'Create' }))
    expect(await screen.findByText('A container with this name already exists.')).toBeInTheDocument()
    expect(onClose).not.toHaveBeenCalled()
  })

  it('appends server field details for INVALID_CONTAINER_SPEC', async () => {
    apiFetch.mockRejectedValue(new ApiError(400, 'bad', 'INVALID_CONTAINER_SPEC', undefined, ['image: must not be blank']))
    wrap(<CreateContainerDialog hostId="h1" isAdmin={false} onClose={() => {}} />)
    type('Image', 'x')
    fireEvent.click(screen.getByRole('button', { name: 'Create' }))
    expect(await screen.findByText(/image: must not be blank/)).toBeInTheDocument()
  })
})

describe('CreateNetworkDialog', () => {
  it('validates subnet, then sends the spec', async () => {
    apiFetch.mockResolvedValue({ id: 'n1', name: 'backend' })
    const onClose = vi.fn()
    wrap(<CreateNetworkDialog hostId="h1" isAdmin={false} onClose={onClose} />)
    type('Name', 'backend')
    type('Subnet (optional)', '300.1.1.1/8')
    fireEvent.click(screen.getByRole('button', { name: 'Create' }))
    expect(screen.getByText('Enter a valid subnet such as 172.30.0.0/16.')).toBeInTheDocument()
    expect(post()).toBeUndefined()
    type('Subnet (optional)', '172.30.0.0/16')
    fireEvent.click(screen.getByLabelText(/Internal/))
    fireEvent.click(screen.getByRole('button', { name: 'Create' }))
    await waitFor(() => expect(onClose).toHaveBeenCalled())
    expect((post()![1] as { body: unknown }).body).toEqual({ name: 'backend', driver: 'bridge', internal: true, subnet: '172.30.0.0/16' })
  })

  it('offers drivers only to admins', () => {
    const { unmount } = wrap(<CreateNetworkDialog hostId="h1" isAdmin={false} onClose={() => {}} />)
    expect(screen.queryByLabelText('Driver')).toBeNull()
    unmount()
    wrap(<CreateNetworkDialog hostId="h1" isAdmin onClose={() => {}} />)
    expect(screen.getByLabelText('Driver')).toBeInTheDocument()
  })

  it('shows NAME_TAKEN inline', async () => {
    apiFetch.mockRejectedValue(new ApiError(409, 'taken', 'NAME_TAKEN'))
    wrap(<CreateNetworkDialog hostId="h1" isAdmin={false} onClose={() => {}} />)
    type('Name', 'backend')
    fireEvent.click(screen.getByRole('button', { name: 'Create' }))
    expect(await screen.findByText('A network with this name already exists.')).toBeInTheDocument()
  })
})

describe('CreateVolumeDialog', () => {
  it('validates the name and sends it', async () => {
    apiFetch.mockResolvedValue({ id: 'data', name: 'data' })
    const onClose = vi.fn()
    wrap(<CreateVolumeDialog hostId="h1" onClose={onClose} />)
    type('Name', '-bad')
    fireEvent.click(screen.getByRole('button', { name: 'Create' }))
    expect(screen.getByRole('alert')).toBeInTheDocument()
    expect(post()).toBeUndefined()
    type('Name', 'data')
    fireEvent.click(screen.getByRole('button', { name: 'Create' }))
    await waitFor(() => expect(onClose).toHaveBeenCalled())
    expect((post()![1] as { body: unknown }).body).toEqual({ name: 'data', driver: 'local' })
  })

  it('shows NAME_TAKEN inline', async () => {
    apiFetch.mockRejectedValue(new ApiError(409, 'taken', 'NAME_TAKEN'))
    wrap(<CreateVolumeDialog hostId="h1" onClose={() => {}} />)
    type('Name', 'data')
    fireEvent.click(screen.getByRole('button', { name: 'Create' }))
    expect(await screen.findByText('A volume with this name already exists.')).toBeInTheDocument()
  })
})
