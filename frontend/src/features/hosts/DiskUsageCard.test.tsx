import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../shared/api/client.ts'
import { DiskUsageCard } from './DiskUsageCard.tsx'

const apiFetch = vi.fn()
vi.mock('../../shared/api/client.ts', async (importActual) => ({
  ...(await importActual<typeof import('../../shared/api/client.ts')>()),
  apiFetch: (...args: unknown[]) => apiFetch(...args),
}))

afterEach(() => {
  cleanup()
  apiFetch.mockReset()
})

const GIB = 1024 ** 3
const MIB = 1024 ** 2

const usage = {
  images: { count: 12, totalSize: 1.2 * GIB, reclaimableSize: 800 * MIB },
  containers: { count: 7, totalSize: 300 * MIB, reclaimableSize: 0 },
  volumes: { count: 4, totalSize: 2 * GIB, reclaimableSize: 512 * MIB },
  buildCache: { count: 3, totalSize: 100 * MIB, reclaimableSize: null },
  largest: [
    { category: 'VOLUME', name: 'pgdata', size: 2 * GIB },
    { category: 'IMAGE', name: 'postgres:16', size: 600 * MIB },
    { category: 'BUILD_CACHE', name: 'Build cache abc123', size: 100 * MIB },
  ],
}

/** The category legend list; the card's Largest items list is a separate list and is never searched here. */
function legend(): HTMLElement {
  const list = document.querySelector<HTMLElement>('.disk__legend')
  if (!list) throw new Error('no legend list')
  return list
}

/** The legend row whose text starts with the category label (list items have no name from content). */
function row(label: string): HTMLElement {
  const found = within(legend())
    .getAllByRole('listitem')
    .find((item) => item.textContent?.startsWith(label))
  if (!found) throw new Error(`no legend row for ${label}`)
  return found
}

function renderCard(props: { onCleanUp?: () => void } = {}) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <DiskUsageCard hostId="a" {...props} />
    </QueryClientProvider>,
  )
}

describe('DiskUsageCard', () => {
  it('shows a bar summary, each category with its size and reclaimable amount', async () => {
    apiFetch.mockResolvedValue(usage)
    renderCard()
    expect(await screen.findByRole('img', { name: /Disk usage/ })).toHaveAccessibleName(/Images 1\.2 GiB/)
    expect(apiFetch).toHaveBeenCalledWith('/hosts/a/disk-usage')
    const images = row('Images')
    expect(images).toHaveTextContent('12 images')
    expect(images).toHaveTextContent('1.2 GiB')
    expect(images).toHaveTextContent('Reclaimable 800 MiB')
    expect(row('Containers')).toHaveTextContent('Reclaimable 0 B')
    expect(row('Volumes')).toHaveTextContent('Reclaimable 512 MiB')
  })

  it('shows unknown for sizes Docker did not report', async () => {
    apiFetch.mockResolvedValue({ ...usage, buildCache: { count: null, totalSize: null, reclaimableSize: null } })
    renderCard()
    await screen.findByRole('img', { name: /Disk usage/ })
    const buildCache = row('Build cache')
    expect(buildCache).toHaveTextContent('unknown')
    expect(buildCache).not.toHaveTextContent('0 B')
    expect(screen.getByRole('img', { name: /Disk usage/ })).toHaveAccessibleName(/Build cache unknown/)
  })

  it('lists the largest items ranked with category label and readable size', async () => {
    apiFetch.mockResolvedValue(usage)
    renderCard()
    const list = await screen.findByRole('list', { name: 'Largest items' })
    const items = within(list).getAllByRole('listitem')
    expect(items).toHaveLength(3)
    expect(items[0]).toHaveTextContent('pgdata')
    expect(items[0]).toHaveTextContent('Volume')
    expect(items[0]).toHaveTextContent('2 GiB')
    expect(items[1]).toHaveTextContent('postgres:16')
    expect(items[1]).toHaveTextContent('Image')
    expect(items[1]).toHaveTextContent('600 MiB')
    expect(items[2]).toHaveTextContent('Build cache abc123')
  })

  it('shows an empty state when there are no largest items', async () => {
    apiFetch.mockResolvedValue({ ...usage, largest: [] })
    renderCard()
    expect(await screen.findByText('No items to show.')).toBeInTheDocument()
    expect(screen.queryByRole('list', { name: 'Largest items' })).not.toBeInTheDocument()
  })

  it('shows a loading state while the first request is pending', () => {
    apiFetch.mockReturnValue(new Promise(() => {}))
    renderCard()
    expect(screen.getByRole('status', { name: 'Loading disk usage' })).toBeInTheDocument()
  })

  it('explains that a deployment-only user cannot see disk usage, without retry', async () => {
    apiFetch.mockRejectedValue(new ApiError(403, 'forbidden', 'FORBIDDEN'))
    renderCard()
    expect(await screen.findByText('Only available with access to the whole environment.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Retry' })).not.toBeInTheDocument()
  })

  it('shows an error with a retry button that requests again', async () => {
    apiFetch.mockRejectedValueOnce(new ApiError(502, 'docker', 'UNREACHABLE')).mockResolvedValueOnce(usage)
    renderCard()
    expect(await screen.findByText('Could not load disk usage.')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }))
    expect(await screen.findByRole('img', { name: /Disk usage/ })).toBeInTheDocument()
    expect(apiFetch).toHaveBeenCalledTimes(2)
  })

  it('refreshes on demand and never polls', async () => {
    apiFetch.mockResolvedValue(usage)
    renderCard()
    await screen.findByRole('img', { name: /Disk usage/ })
    fireEvent.click(screen.getByRole('button', { name: 'Refresh disk usage' }))
    await waitFor(() => expect(apiFetch).toHaveBeenCalledTimes(2))
  })

  it('offers the cleanup action only when a handler is given', async () => {
    apiFetch.mockResolvedValue(usage)
    const onCleanUp = vi.fn()
    renderCard({ onCleanUp })
    fireEvent.click(await screen.findByRole('button', { name: 'Review cleanup' }))
    expect(onCleanUp).toHaveBeenCalledTimes(1)
    cleanup()
    renderCard()
    await screen.findByRole('img', { name: /Disk usage/ })
    expect(screen.queryByRole('button', { name: 'Review cleanup' })).not.toBeInTheDocument()
  })
})
