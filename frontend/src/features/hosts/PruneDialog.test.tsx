import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../shared/api/client.ts'
import { PruneDialog } from './PruneDialog.tsx'

const apiFetch = vi.fn()
vi.mock('../../shared/api/client.ts', async (importActual) => ({
  ...(await importActual<typeof import('../../shared/api/client.ts')>()),
  apiFetch: (...args: unknown[]) => apiFetch(...args),
}))

afterEach(() => {
  cleanup()
  apiFetch.mockReset()
})

const group = (count: number, sizeBytes = 0) => ({ count, sizeBytes, items: [] })
const preview = {
  containers: group(2),
  images: group(3, 3 * 1024 ** 2),
  volumes: group(1),
  networks: group(4),
  managedVolumesExcluded: 2,
}
const counts = (removed: number) => ({ removed, skipped: 0, reclaimedBytes: 0 })
const result = {
  containers: counts(2),
  images: { removed: 3, skipped: 1, reclaimedBytes: 3 * 1024 ** 2 },
  volumes: counts(0),
  networks: counts(4),
  reclaimedBytes: 3 * 1024 ** 2,
}

function setup(post: () => Promise<unknown> = () => Promise.resolve(result)) {
  apiFetch.mockImplementation((_path: string, init?: { method?: string }) =>
    init?.method === 'POST' ? post() : Promise.resolve(preview),
  )
  render(
    <QueryClientProvider client={new QueryClient()}>
      <PruneDialog hostId="h1" onClose={() => {}} />
    </QueryClientProvider>,
  )
}

const postCall = () => apiFetch.mock.calls.find((c) => (c[1] as { method?: string } | undefined)?.method === 'POST')

describe('PruneDialog', () => {
  it('shows the preview and sends the selected options', async () => {
    setup()
    const pv = await screen.findByLabelText('Preview')
    expect(pv).toHaveTextContent('3 images (3 MiB)')
    expect(pv).toHaveTextContent('4 networks')
    expect(pv).not.toHaveTextContent('volumes')
    fireEvent.click(screen.getByLabelText('Unused volumes'))
    expect(await screen.findByText(/2 volume\(s\) of managed deployments are kept/)).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Images'), { target: { value: 'UNUSED' } })
    await waitFor(() => expect(apiFetch).toHaveBeenCalledWith('/hosts/h1/prune/preview?images=UNUSED&includeManagedVolumes=false'))
    fireEvent.click(await screen.findByRole('button', { name: 'Clean up' }))
    expect(await screen.findByRole('status')).toHaveTextContent('3 images removed, 1 skipped')
    expect(postCall()).toEqual([
      '/hosts/h1/prune',
      { method: 'POST', body: { containers: true, images: 'UNUSED', volumes: true, networks: true, includeManagedVolumes: false } },
    ])
  })

  it('disables the action when nothing is selected', async () => {
    setup()
    await screen.findByLabelText('Preview')
    fireEvent.click(screen.getByLabelText('Stopped containers'))
    fireEvent.click(screen.getByLabelText('Unused networks'))
    fireEvent.change(screen.getByLabelText('Images'), { target: { value: 'NONE' } })
    expect(screen.getByRole('button', { name: 'Clean up' })).toBeDisabled()
  })

  it('shows a 403 inline', async () => {
    setup(() => Promise.reject(new ApiError(403, '{"code":"FORBIDDEN"}', 'FORBIDDEN')))
    await screen.findByLabelText('Preview')
    fireEvent.click(screen.getByRole('button', { name: 'Clean up' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('You are not allowed to clean up this environment.')
  })
})
