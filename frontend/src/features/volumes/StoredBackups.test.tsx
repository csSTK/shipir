import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../shared/api/client.ts'
import { StoredBackupsDialog } from './StoredBackupsDialog.tsx'
import { VolumesPage } from './VolumesPage.tsx'

const apiFetch = vi.fn()
vi.mock('../../shared/api/client.ts', async (importActual) => ({
  ...(await importActual<typeof import('../../shared/api/client.ts')>()),
  apiFetch: (...args: unknown[]) => apiFetch(...args),
}))
let role = 'OPERATOR'
vi.mock('../auth/index.ts', () => ({ useCurrentUser: () => ({ data: { username: 'u', role } }) }))

const fetchMock = vi.fn()
beforeEach(() => {
  role = 'OPERATOR'
  vi.stubGlobal('fetch', fetchMock)
  URL.createObjectURL = vi.fn(() => 'blob:x')
  URL.revokeObjectURL = vi.fn()
})
afterEach(() => {
  cleanup()
  apiFetch.mockReset()
  fetchMock.mockReset()
  vi.unstubAllGlobals()
})

const stored = [
  { name: 'cache-20260102T030405Z.tar', sizeBytes: 2048, createdAt: '2026-01-02T03:04:05Z' },
  { name: 'cache-20260101T000000Z.tar', sizeBytes: 12, createdAt: '2026-01-01T00:00:00Z' },
]
const cache = { name: 'cache', driver: 'local', mountpoint: '/v/cache', created: null, inUse: false }

function mockApi(list: unknown = stored) {
  apiFetch.mockImplementation((path: string) => {
    if (path === '/hosts/h1/volumes') return Promise.resolve([cache])
    if (path === '/hosts/h1/volume-backup-schedules') return Promise.resolve([])
    if (path === '/hosts/h1/volumes/cache/stored-backups') return list instanceof Error ? Promise.reject(list) : Promise.resolve(list)
    return Promise.resolve(undefined)
  })
}

function wrap(ui: React.ReactNode) {
  return (
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter initialEntries={['/environments/h1/volumes']}>
        <Routes>
          <Route path="/environments/:id/volumes" element={ui} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>
  )
}

describe('StoredBackupsDialog', () => {
  it('lists the stored archives with size and time', async () => {
    mockApi()
    render(wrap(<StoredBackupsDialog hostId="h1" volume="cache" onClose={() => {}} />))
    const rows = await screen.findAllByRole('listitem')
    expect(rows).toHaveLength(2)
    expect(rows[0]).toHaveTextContent('cache-20260102T030405Z.tar')
    expect(rows[0]).toHaveTextContent('2 KiB')
    expect(rows[1]).toHaveTextContent('12 B')
  })

  it('says so when nothing was stored yet', async () => {
    mockApi([])
    render(wrap(<StoredBackupsDialog hostId="h1" volume="cache" onClose={() => {}} />))
    expect(await screen.findByText('No stored backups yet.')).toBeInTheDocument()
  })

  it('shows a failed load as an alert', async () => {
    mockApi(new ApiError(403, '{}', 'FORBIDDEN'))
    render(wrap(<StoredBackupsDialog hostId="h1" volume="cache" onClose={() => {}} />))
    expect(await screen.findByRole('alert')).toHaveTextContent('You are not allowed to do this.')
  })

  it('downloads one archive by its exact name', async () => {
    mockApi()
    fetchMock.mockResolvedValue(
      new Response(new TextEncoder().encode('tar'), { status: 200, headers: { 'Content-Disposition': 'attachment; filename="cache-20260101T000000Z.tar"' } }),
    )
    render(wrap(<StoredBackupsDialog hostId="h1" volume="cache" onClose={() => {}} />))
    const row = (await screen.findAllByRole('listitem'))[1]
    fireEvent.click(within(row).getByRole('button', { name: 'Download cache-20260101T000000Z.tar' }))
    await waitFor(() =>
      expect(fetchMock).toHaveBeenCalledWith('/api/hosts/h1/volumes/cache/stored-backups/cache-20260101T000000Z.tar', expect.anything()),
    )
    await waitFor(() => expect(URL.createObjectURL).toHaveBeenCalled())
  })

  it('shows a failed download as an alert', async () => {
    mockApi()
    fetchMock.mockResolvedValue(new Response(JSON.stringify({ code: 'STORED_BACKUP_NOT_FOUND' }), { status: 404 }))
    render(wrap(<StoredBackupsDialog hostId="h1" volume="cache" onClose={() => {}} />))
    fireEvent.click(await screen.findByRole('button', { name: 'Download cache-20260102T030405Z.tar' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('This stored backup no longer exists.')
  })
})

describe('VolumesPage stored backups', () => {
  it('opens the stored backups of a volume from the row menu', async () => {
    mockApi()
    render(wrap(<VolumesPage />))
    fireEvent.pointerDown(await screen.findByRole('button', { name: 'More actions for cache' }), { button: 0, pointerType: 'mouse' })
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Stored backups…' }))
    expect(await screen.findByRole('dialog', { name: 'Stored backups of cache' })).toBeInTheDocument()
    expect(await screen.findByText('cache-20260102T030405Z.tar')).toBeInTheDocument()
  })

  it('offers no stored backups to viewers', async () => {
    role = 'VIEWER'
    mockApi()
    render(wrap(<VolumesPage />))
    await screen.findByText('cache')
    expect(screen.queryByRole('button', { name: /More actions/ })).toBeNull()
    expect(apiFetch).not.toHaveBeenCalledWith('/hosts/h1/volumes/cache/stored-backups')
  })
})
