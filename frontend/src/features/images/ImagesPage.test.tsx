import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../shared/api/client.ts'

import { ImagesPage } from './ImagesPage.tsx'

const apiFetch = vi.fn()
vi.mock('../../shared/api/client.ts', async (importActual) => ({
  ...(await importActual<typeof import('../../shared/api/client.ts')>()),
  apiFetch: (...args: unknown[]) => apiFetch(...args),
}))

afterEach(() => {
  cleanup()
  apiFetch.mockReset()
})

const images = [
  { id: 'sha256:aaaaaaaaaaaaaaaaaaaa', repoTags: ['nginx:1', 'nginx:latest'], size: 5 * 1024 ** 2, created: '2026-10-01T10:00:00Z', inUse: true },
  { id: 'sha256:bbbbbbbbbbbbbbbbbbbb', repoTags: ['redis:7'], size: 2 * 1024 ** 3, created: '2026-09-01T10:00:00Z', inUse: false },
  { id: 'sha256:cccccccccccccccccccc', repoTags: [], size: null, created: '2026-08-01T10:00:00Z', inUse: false },
]

function renderPage() {
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter initialEntries={['/environments/h1/images']}>
        <Routes>
          <Route path="/environments/:id/images" element={<ImagesPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

function firstCells() {
  return screen
    .getAllByRole('row')
    .slice(1)
    .map((r) => within(r).getAllByRole('cell')[0].textContent)
}

describe('ImagesPage', () => {
  it('loads images with tags, short id, size and usage badge', async () => {
    apiFetch.mockResolvedValue(images)
    renderPage()
    expect(screen.getByRole('status', { name: 'Loading images' })).toBeInTheDocument()
    expect(await screen.findByText('nginx:1')).toBeInTheDocument()
    expect(apiFetch).toHaveBeenCalledWith('/hosts/h1/images')
    const nginx = screen.getByRole('row', { name: /nginx:1/ })
    expect(within(nginx).getByText('nginx:latest')).toBeInTheDocument()
    expect(within(nginx).getByText('aaaaaaaaaaaa')).toBeInTheDocument()
    expect(within(nginx).getByText('5 MiB')).toBeInTheDocument()
    expect(within(nginx).getByText('In use')).toBeInTheDocument()
    const redis = screen.getByRole('row', { name: /redis:7/ })
    expect(within(redis).getByText('2 GiB')).toBeInTheDocument()
    expect(within(redis).getByText('Unused')).toBeInTheDocument()
  })

  it('shows <none> for dangling images and a dash for unknown size', async () => {
    apiFetch.mockResolvedValue(images)
    renderPage()
    const row = (await screen.findByText('<none>')).closest('tr')!
    expect(within(row).getAllByText('–').length).toBeGreaterThan(0)
  })

  it('searches by tag and id and sorts by size', async () => {
    apiFetch.mockResolvedValue(images)
    renderPage()
    await screen.findByText('nginx:1')
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'redis' } })
    expect(firstCells()).toEqual(['redis:7'])
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'cccc' } })
    expect(firstCells()).toEqual(['<none>'])
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: '' } })
    fireEvent.click(screen.getByRole('button', { name: 'Size' }))
    expect(firstCells()).toEqual(['nginx:1nginx:latest', 'redis:7', '<none>'])
  })

  it('maps errors and shows the empty state', async () => {
    apiFetch.mockRejectedValue(new ApiError(502, 'x', 'UNREACHABLE'))
    renderPage()
    expect(await screen.findByRole('alert')).toHaveTextContent('Could not reach the Docker host.')
    cleanup()
    apiFetch.mockResolvedValue([])
    renderPage()
    expect(await screen.findByText('No images in this environment.')).toBeInTheDocument()
  })
})
