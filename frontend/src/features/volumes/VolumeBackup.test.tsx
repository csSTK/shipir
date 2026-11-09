import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../shared/api/client.ts'
import { BackupSchedulesDialog } from './BackupSchedulesDialog.tsx'
import { RestoreVolumeDialog } from './RestoreVolumeDialog.tsx'
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

const pgdata = { name: 'pgdata', driver: 'local', mountpoint: '/v/pgdata', created: null, inUse: true }
const cache = { name: 'cache', driver: 'local', mountpoint: '/v/cache', created: null, inUse: false }
const volumes = [pgdata, cache]
const schedule = (over = {}) => ({
  id: 's1',
  volumeName: 'pgdata',
  intervalHours: 24,
  keepCount: 7,
  nextRunAt: '2026-10-26T10:00:00Z',
  lastRunAt: '2026-10-25T10:00:00Z',
  lastStatus: 'SUCCESS',
  ...over,
})

function mockApi(schedules: unknown[] = []) {
  apiFetch.mockImplementation((path: string, init?: { method?: string }) => {
    if (path === '/hosts/h1/volumes') return Promise.resolve(volumes)
    if (path === '/hosts/h1/volume-backup-schedules') return Promise.resolve(init?.method === 'POST' ? schedule() : schedules)
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

async function openMenu(name: string, item: string) {
  fireEvent.pointerDown(await screen.findByRole('button', { name: `More actions for ${name}` }), { button: 0, pointerType: 'mouse' })
  fireEvent.click(await screen.findByRole('menuitem', { name: item }))
}

describe('VolumesPage backup', () => {
  it('offers Backup, Restore and Schedule in the row menu for operators', async () => {
    mockApi()
    render(wrap(<VolumesPage />))
    fireEvent.pointerDown(await screen.findByRole('button', { name: 'More actions for cache' }), { button: 0, pointerType: 'mouse' })
    for (const name of ['Backup', 'Restore…', 'Schedule backups…', 'Delete']) {
      expect(await screen.findByRole('menuitem', { name })).toBeInTheDocument()
    }
  })

  it('hides backup actions and does not load schedules for viewers', async () => {
    role = 'VIEWER'
    mockApi()
    render(wrap(<VolumesPage />))
    await screen.findByText('pgdata')
    expect(screen.queryByRole('button', { name: /More actions/ })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Backup schedules' })).toBeNull()
    expect(apiFetch).not.toHaveBeenCalledWith('/hosts/h1/volume-backup-schedules')
  })

  it('shows the last backup result per volume', async () => {
    mockApi([schedule(), schedule({ id: 's2', volumeName: 'cache', lastStatus: 'FAILED' })])
    render(wrap(<VolumesPage />))
    const ok = within((await screen.findByText('pgdata')).closest('tr')!)
    expect(await ok.findByText('Backup OK')).toBeInTheDocument()
    expect(within(screen.getByText('cache').closest('tr')!).getByText('Backup failed')).toBeInTheDocument()
  })

  it('downloads the archive and warns when the volume is in use', async () => {
    mockApi()
    fetchMock.mockResolvedValue(
      new Response(new TextEncoder().encode('tar'), { status: 200, headers: { 'Content-Disposition': 'attachment; filename="pgdata-1.tar"' } }),
    )
    render(wrap(<VolumesPage />))
    await openMenu('pgdata', 'Backup')
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/hosts/h1/volumes/pgdata/backup', expect.anything()))
    expect(await screen.findByRole('status')).toHaveTextContent(/pgdata is in use.*inconsistent/)
    expect(URL.createObjectURL).toHaveBeenCalled()
  })

  it('disables Backup while a download is running, so it cannot start twice', async () => {
    mockApi()
    let finish: (r: Response) => void = () => {}
    fetchMock.mockReturnValue(new Promise<Response>((r) => (finish = r)))
    render(wrap(<VolumesPage />))
    await openMenu('pgdata', 'Backup')
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1))
    fireEvent.pointerDown(await screen.findByRole('button', { name: 'More actions for cache' }), { button: 0, pointerType: 'mouse' })
    expect(await screen.findByRole('menuitem', { name: 'Backup' })).toHaveAttribute('aria-disabled', 'true')
    finish(new Response('x', { status: 200 }))
    await waitFor(() => expect(URL.createObjectURL).toHaveBeenCalled())
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('shows a failed backup as an alert', async () => {
    mockApi()
    fetchMock.mockResolvedValue(new Response(JSON.stringify({ code: 'RESOURCE_NOT_FOUND' }), { status: 404 }))
    render(wrap(<VolumesPage />))
    await openMenu('cache', 'Backup')
    expect(await screen.findByRole('alert')).toHaveTextContent('This volume no longer exists.')
  })
})

describe('RestoreVolumeDialog', () => {
  const file = () => new File(['x'], 'pgdata.tar', { type: 'application/x-tar' })
  function setup(volume = cache) {
    const onDone = vi.fn()
    render(wrap(<RestoreVolumeDialog hostId="h1" volume={volume} onClose={() => {}} onDone={onDone} />))
    return onDone
  }
  const pick = (f: File) => fireEvent.change(screen.getByLabelText('Backup archive (.tar)'), { target: { files: [f] } })
  const body = () => fetchMock.mock.calls[0][1].body as FormData

  it('states overlay semantics and the size limit', () => {
    setup()
    expect(screen.getByRole('dialog', { name: 'Restore cache' })).toHaveTextContent(/overlaid onto the existing volume.*stay/)
    expect(screen.getByRole('dialog')).toHaveTextContent('100 MB')
    expect(screen.getByRole('button', { name: 'Restore' })).toBeDisabled()
  })

  it('uploads the file with confirm=false for an unused volume', async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }))
    const onDone = setup()
    pick(file())
    fireEvent.click(screen.getByRole('button', { name: 'Restore' }))
    await waitFor(() => expect(onDone).toHaveBeenCalled())
    expect(fetchMock.mock.calls[0][0]).toBe('/api/hosts/h1/volumes/cache/restore')
    expect(body().get('confirm')).toBe('false')
    expect((body().get('file') as File).name).toBe('pgdata.tar')
  })

  it('requires an explicit confirmation for a volume in use', async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }))
    setup(pgdata)
    pick(file())
    expect(screen.getByRole('button', { name: 'Restore' })).toBeDisabled()
    fireEvent.click(screen.getByRole('switch', { name: /in use/i }))
    fireEvent.click(screen.getByRole('button', { name: 'Restore' }))
    await waitFor(() => expect(fetchMock).toHaveBeenCalled())
    expect(body().get('confirm')).toBe('true')
  })

  it('asks for the confirmation when the server reports the volume in use', async () => {
    fetchMock.mockResolvedValue(new Response(JSON.stringify({ code: 'VOLUME_IN_USE' }), { status: 409 }))
    setup()
    pick(file())
    fireEvent.click(screen.getByRole('button', { name: 'Restore' }))
    expect(await screen.findByRole('switch', { name: /in use/i })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Restore' })).toBeDisabled()
  })

  it('rejects files over 100 MB without uploading', () => {
    setup()
    const big = new File(['x'], 'big.tar')
    Object.defineProperty(big, 'size', { value: 101 * 1024 * 1024 })
    pick(big)
    expect(screen.getByRole('alert')).toHaveTextContent('larger than 100 MB')
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('shows invalid archives inline', async () => {
    fetchMock.mockResolvedValue(new Response(JSON.stringify({ code: 'INVALID_ARCHIVE' }), { status: 400 }))
    setup()
    pick(file())
    fireEvent.click(screen.getByRole('button', { name: 'Restore' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('not a valid backup archive')
  })
})

describe('BackupSchedulesDialog', () => {
  function setup(schedules: unknown[] = [schedule()]) {
    mockApi(schedules)
    render(wrap(<BackupSchedulesDialog hostId="h1" volumes={volumes} initialVolume="cache" onClose={() => {}} />))
  }

  it('lists the schedules with interval, retention and last result', async () => {
    setup()
    const row = await screen.findByRole('listitem')
    expect(row).toHaveTextContent('every 24 h')
    expect(row).toHaveTextContent('keep 7')
    expect(within(row).getByText('Backup OK')).toBeInTheDocument()
  })

  it('opens the stored backups of a schedule whose volume no longer exists in Docker', async () => {
    mockApi([schedule({ volumeName: 'gone' })])
    apiFetch.mockImplementation((path: string, init?: { method?: string }) => {
      if (path === '/hosts/h1/volume-backup-schedules') return Promise.resolve([schedule({ volumeName: 'gone' })])
      if (path === '/hosts/h1/volumes/gone/stored-backups')
        return Promise.resolve([{ name: 'gone-20260101T000000Z.tar', sizeBytes: 12, createdAt: '2026-01-01T00:00:00Z' }])
      return Promise.resolve(init ? undefined : [])
    })
    render(wrap(<BackupSchedulesDialog hostId="h1" volumes={volumes} initialVolume="cache" onClose={() => {}} />))
    fireEvent.click(await screen.findByRole('button', { name: 'Stored backups of gone' }))
    const dialog = await screen.findByRole('dialog', { name: 'Stored backups of gone' })
    expect(await within(dialog).findByText('gone-20260101T000000Z.tar')).toBeInTheDocument()
    expect(within(dialog).getByRole('button', { name: 'Download gone-20260101T000000Z.tar' })).toBeInTheDocument()
    expect(within(dialog).getByRole('button', { name: 'Restore gone-20260101T000000Z.tar' })).toBeInTheDocument()
  })

  it('hints to create a deleted volume again before restoring its stored archive', async () => {
    apiFetch.mockImplementation((path: string) => {
      if (path === '/hosts/h1/volume-backup-schedules') return Promise.resolve([schedule({ volumeName: 'gone' })])
      if (path === '/hosts/h1/volumes/gone/stored-backups')
        return Promise.resolve([{ name: 'gone-20260101T000000Z.tar', sizeBytes: 12, createdAt: '2026-01-01T00:00:00Z' }])
      return Promise.resolve([])
    })
    render(wrap(<BackupSchedulesDialog hostId="h1" volumes={volumes} initialVolume="cache" onClose={() => {}} />))
    fireEvent.click(await screen.findByRole('button', { name: 'Stored backups of gone' }))
    const dialog = await screen.findByRole('dialog', { name: 'Stored backups of gone' })
    fireEvent.click(await within(dialog).findByRole('button', { name: 'Restore gone-20260101T000000Z.tar' }))
    expect(await within(dialog).findByText('Create the volume first, then restore.')).toBeInTheDocument()
    expect(within(dialog).getByRole('button', { name: 'Restore' })).toBeDisabled()
    expect(apiFetch.mock.calls.filter(([path]) => String(path).includes('/restore'))).toHaveLength(0)
  })

  const deleteCalls = () => apiFetch.mock.calls.filter(([, init]) => init?.method === 'DELETE')

  it('asks before deleting a schedule and says its stored backups go too', async () => {
    setup()
    fireEvent.click(await screen.findByRole('button', { name: 'Delete schedule for pgdata' }))
    const confirm = await screen.findByRole('dialog', { name: 'Delete schedule for pgdata?' })
    expect(confirm).toHaveTextContent(/schedule and all its stored backups/)
    expect(deleteCalls()).toHaveLength(0)
  })

  it('keeps everything when the deletion is cancelled and returns focus to the trigger', async () => {
    setup()
    const trigger = await screen.findByRole('button', { name: 'Delete schedule for pgdata' })
    trigger.focus()
    fireEvent.click(trigger)
    const confirm = await screen.findByRole('dialog', { name: 'Delete schedule for pgdata?' })
    fireEvent.click(within(confirm).getByRole('button', { name: 'Cancel' }))
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Delete schedule for pgdata?' })).toBeNull())
    expect(deleteCalls()).toHaveLength(0)
    await waitFor(() => expect(trigger).toHaveFocus())
  })

  it('deletes the schedule once after confirming', async () => {
    setup()
    fireEvent.click(await screen.findByRole('button', { name: 'Delete schedule for pgdata' }))
    const confirm = await screen.findByRole('dialog', { name: 'Delete schedule for pgdata?' })
    fireEvent.click(within(confirm).getByRole('button', { name: 'Delete' }))
    await waitFor(() => expect(apiFetch).toHaveBeenCalledWith('/hosts/h1/volume-backup-schedules/s1', { method: 'DELETE' }))
    expect(deleteCalls()).toHaveLength(1)
  })

  it('refreshes the stored backups of the host after a schedule was deleted', async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const spy = vi.spyOn(client, 'invalidateQueries')
    mockApi([schedule()])
    render(
      <QueryClientProvider client={client}>
        <MemoryRouter>
          <BackupSchedulesDialog hostId="h1" volumes={volumes} initialVolume="cache" onClose={() => {}} />
        </MemoryRouter>
      </QueryClientProvider>,
    )
    fireEvent.click(await screen.findByRole('button', { name: 'Delete schedule for pgdata' }))
    fireEvent.click(within(await screen.findByRole('dialog', { name: 'Delete schedule for pgdata?' })).getByRole('button', { name: 'Delete' }))
    await waitFor(() => expect(spy).toHaveBeenCalledWith({ queryKey: ['hosts', 'h1', 'volumes'] }))
  })

  it('creates a schedule for the preselected volume', async () => {
    setup([])
    fireEvent.change(await screen.findByLabelText('Interval (hours)'), { target: { value: '12' } })
    fireEvent.change(screen.getByLabelText('Keep newest'), { target: { value: '3' } })
    fireEvent.click(screen.getByRole('button', { name: 'Add schedule' }))
    await waitFor(() =>
      expect(apiFetch).toHaveBeenCalledWith('/hosts/h1/volume-backup-schedules', {
        method: 'POST',
        body: { volumeName: 'cache', intervalHours: 12, keepCount: 3 },
      }),
    )
  })

  it('validates ranges and maps SCHEDULE_EXISTS', async () => {
    setup([])
    fireEvent.change(await screen.findByLabelText('Interval (hours)'), { target: { value: '9000' } })
    fireEvent.click(screen.getByRole('button', { name: 'Add schedule' }))
    expect(await screen.findByText('Enter 1 to 8760 hours.')).toBeInTheDocument()
    expect(apiFetch).not.toHaveBeenCalledWith('/hosts/h1/volume-backup-schedules', expect.objectContaining({ method: 'POST' }))
    fireEvent.change(screen.getByLabelText('Interval (hours)'), { target: { value: '24' } })
    apiFetch.mockImplementation((_p: string, init?: { method?: string }) =>
      init?.method === 'POST' ? Promise.reject(new ApiError(409, '{}', 'SCHEDULE_EXISTS')) : Promise.resolve([]),
    )
    fireEvent.click(screen.getByRole('button', { name: 'Add schedule' }))
    expect(await screen.findByText('This volume already has a backup schedule.')).toBeInTheDocument()
  })

  it('disables Add schedule with a hint when the environment has no volumes', async () => {
    mockApi([])
    render(wrap(<BackupSchedulesDialog hostId="h1" volumes={[]} onClose={() => {}} />))
    expect(await screen.findByText('No volumes in this environment yet. Create a volume first.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Add schedule' })).toBeDisabled()
  })
})
