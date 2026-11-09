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

  it('reloads the list when a download finds the archive gone', async () => {
    let calls = 0
    apiFetch.mockImplementation((path: string) => {
      if (path === '/hosts/h1/volumes/cache/stored-backups') return Promise.resolve(++calls === 1 ? stored : [stored[1]])
      return Promise.resolve(undefined)
    })
    fetchMock.mockResolvedValue(new Response(JSON.stringify({ code: 'STORED_BACKUP_NOT_FOUND' }), { status: 404 }))
    render(wrap(<StoredBackupsDialog hostId="h1" volume="cache" onClose={() => {}} />))
    fireEvent.click(await screen.findByRole('button', { name: 'Download cache-20260102T030405Z.tar' }))
    await waitFor(() => expect(screen.getAllByRole('listitem')).toHaveLength(1))
    expect(screen.queryByText('cache-20260102T030405Z.tar')).toBeNull()
  })

  it('shows a failed download as an alert', async () => {
    mockApi()
    fetchMock.mockResolvedValue(new Response(JSON.stringify({ code: 'STORED_BACKUP_NOT_FOUND' }), { status: 404 }))
    render(wrap(<StoredBackupsDialog hostId="h1" volume="cache" onClose={() => {}} />))
    fireEvent.click(await screen.findByRole('button', { name: 'Download cache-20260102T030405Z.tar' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('This stored backup no longer exists.')
  })
})

describe('StoredBackupsDialog restore', () => {
  const first = 'cache-20260102T030405Z.tar'
  const restorePath = `/hosts/h1/volumes/cache/stored-backups/${first}/restore?confirm=`

  function setup(props: { inUse?: boolean } = {}) {
    const onDone = vi.fn()
    render(wrap(<StoredBackupsDialog hostId="h1" volume="cache" onClose={() => {}} onDone={onDone} {...props} />))
    return onDone
  }
  async function choose(name = first) {
    fireEvent.click(await screen.findByRole('button', { name: `Restore ${name}` }))
  }
  const restoreCalls = () => apiFetch.mock.calls.filter(([path]) => String(path).includes('/restore'))

  it('asks before restoring and states the overlay semantics', async () => {
    mockApi()
    setup()
    await choose()
    expect(await screen.findByText(/overlaid onto the existing volume/)).toBeInTheDocument()
    expect(restoreCalls()).toHaveLength(0)
  })

  it('restores the chosen archive of an unused volume without confirm and reports it', async () => {
    mockApi()
    const onDone = setup()
    await choose()
    fireEvent.click(await screen.findByRole('button', { name: 'Restore' }))
    await waitFor(() => expect(onDone).toHaveBeenCalledWith(`Restored ${first} into cache.`))
    expect(restoreCalls()).toEqual([[`${restorePath}false`, { method: 'POST' }]])
  })

  it('needs the confirm switch for a volume in use and sends confirm=true', async () => {
    mockApi()
    const onDone = setup({ inUse: true })
    await choose()
    const restore = await screen.findByRole('button', { name: 'Restore' })
    expect(restore).toBeDisabled()
    fireEvent.click(screen.getByRole('switch'))
    fireEvent.click(restore)
    await waitFor(() => expect(onDone).toHaveBeenCalled())
    expect(restoreCalls()).toEqual([[`${restorePath}true`, { method: 'POST' }]])
  })

  it('shows the confirm switch after a 409 and retries only with it', async () => {
    mockApi()
    const base = apiFetch.getMockImplementation()!
    apiFetch.mockImplementation((path: string, ...rest: unknown[]) =>
      path === `${restorePath}false` ? Promise.reject(new ApiError(409, '{}', 'VOLUME_IN_USE')) : base(path, ...rest),
    )
    const onDone = setup()
    await choose()
    fireEvent.click(await screen.findByRole('button', { name: 'Restore' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('This volume is in use')
    expect(screen.getByRole('button', { name: 'Restore' })).toBeDisabled()
    fireEvent.click(screen.getByRole('switch'))
    fireEvent.click(screen.getByRole('button', { name: 'Restore' }))
    await waitFor(() => expect(onDone).toHaveBeenCalled())
    expect(restoreCalls().map(([p]) => p)).toEqual([`${restorePath}false`, `${restorePath}true`])
  })

  it('shows a rejected archive as an alert and does not report success', async () => {
    mockApi()
    const base = apiFetch.getMockImplementation()!
    apiFetch.mockImplementation((path: string, ...rest: unknown[]) =>
      String(path).includes('/restore') ? Promise.reject(new ApiError(400, '{}', 'INVALID_ARCHIVE')) : base(path, ...rest),
    )
    const onDone = setup()
    await choose()
    fireEvent.click(await screen.findByRole('button', { name: 'Restore' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('not a valid backup archive')
    expect(onDone).not.toHaveBeenCalled()
  })

  it('moves focus into the restore step as a labelled group and back to the row button on cancel', async () => {
    mockApi()
    setup()
    await choose()
    const group = await screen.findByRole('group', { name: `Restore ${first} into cache?` })
    await waitFor(() => expect(group.contains(document.activeElement)).toBe(true))
    fireEvent.click(within(group).getByRole('button', { name: 'Cancel' }))
    await waitFor(() => expect(screen.getByRole('button', { name: `Restore ${first}` })).toHaveFocus())
    expect(screen.queryByRole('group')).toBeNull()
  })

  it('reloads the list when a restore finds the archive gone', async () => {
    let calls = 0
    apiFetch.mockImplementation((path: string) => {
      if (path === '/hosts/h1/volumes/cache/stored-backups') return Promise.resolve(++calls === 1 ? stored : [stored[1]])
      if (String(path).includes('/restore')) return Promise.reject(new ApiError(404, '{}', 'STORED_BACKUP_NOT_FOUND'))
      return Promise.resolve(undefined)
    })
    setup()
    await choose()
    fireEvent.click(await screen.findByRole('button', { name: 'Restore' }))
    await waitFor(() => expect(screen.queryByRole('button', { name: `Restore ${first}` })).toBeNull())
    expect(await screen.findByRole('alert')).toHaveTextContent('no longer exists')
  })

  it('says to create the volume first and keeps Restore disabled when the volume is gone', async () => {
    mockApi()
    const onDone = vi.fn()
    render(wrap(<StoredBackupsDialog hostId="h1" volume="cache" volumeMissing onClose={() => {}} onDone={onDone} />))
    await choose()
    expect(await screen.findByText('Create the volume first, then restore.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Restore' })).toBeDisabled()
    expect(restoreCalls()).toHaveLength(0)
    expect(onDone).not.toHaveBeenCalled()
  })

  it('can cancel the restore step', async () => {
    mockApi()
    setup()
    await choose()
    fireEvent.click(await screen.findByRole('button', { name: 'Cancel' }))
    expect(screen.queryByText(/overlaid onto the existing volume/)).toBeNull()
    expect(restoreCalls()).toHaveLength(0)
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
