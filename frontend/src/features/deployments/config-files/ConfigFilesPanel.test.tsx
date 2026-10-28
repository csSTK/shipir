import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../../shared/api/client.ts'
import { describeConfigError, languageFor } from './api.ts'
import { ConfigFilesPanel } from './ConfigFilesPanel.tsx'

const apiFetch = vi.fn()
vi.mock('../../../shared/api/client.ts', async (importActual) => ({
  ...(await importActual<typeof import('../../../shared/api/client.ts')>()),
  apiFetch: (...args: unknown[]) => apiFetch(...args),
}))

afterEach(() => {
  cleanup()
  apiFetch.mockReset()
})

const BASE = '/hosts/h1/managed-deployments/d1/files'
const FILE = `${BASE}?path=conf%2Fapp.conf`
const tree = [
  { path: 'conf', directory: true, size: 0, modifiedAt: '2026-10-01T10:00:00Z', readOnly: false },
  { path: 'conf/app.conf', directory: false, size: 5, modifiedAt: '2026-10-01T10:00:00Z', readOnly: false },
  { path: 'repo/ro.txt', directory: false, size: 2, modifiedAt: '2026-10-01T10:00:00Z', readOnly: true },
]
const file = { path: 'conf/app.conf', content: 'a=1\n', size: 4, modifiedAt: '2026-10-01T10:00:00Z', readOnly: false }
const backups = [{ id: 'b1', createdAt: '2026-10-01T09:00:00Z', size: 3 }]

type Handler = (init?: { method?: string; body?: unknown }) => unknown
function serve(overrides: Record<string, Handler> = {}) {
  apiFetch.mockImplementation((path: string, init?: { method?: string; body?: unknown }) => {
    const key = `${init?.method ?? 'GET'} ${path}`
    const h = overrides[key]
    if (h) {
      const r = h(init)
      return r instanceof Error ? Promise.reject(r) : Promise.resolve(r)
    }
    if (path === BASE) return Promise.resolve(tree)
    if (path === FILE) return Promise.resolve(file)
    if (path === `${BASE}/backups?path=conf%2Fapp.conf`) return Promise.resolve(backups)
    if (path === `${BASE}?path=repo%2Fro.txt`) return Promise.resolve({ ...file, path: 'repo/ro.txt', readOnly: true })
    if (path.startsWith(`${BASE}/backups`)) return Promise.resolve([])
    return Promise.reject(new ApiError(404, 'x', 'FILE_NOT_FOUND'))
  })
}

const renderPanel = (canWrite: boolean) =>
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <ConfigFilesPanel hostId="h1" deploymentId="d1" canWrite={canWrite} />
    </QueryClientProvider>,
  )

describe('ConfigFilesPanel', () => {
  it('edits and saves a file, Save only enabled when dirty', async () => {
    const put = vi.fn(() => ({ ...file, content: 'a=2\n' }))
    serve({ [`PUT ${FILE}`]: put })
    renderPanel(true)
    fireEvent.click(await screen.findByRole('button', { name: 'app.conf' }))
    const editor = await screen.findByLabelText('Contents of conf/app.conf')
    const save = screen.getByRole('button', { name: 'Save' })
    expect(save).toBeDisabled()
    fireEvent.change(editor, { target: { value: 'a=2\n' } })
    expect(screen.getByText(/Unsaved changes/)).toBeInTheDocument()
    fireEvent.click(save)
    await waitFor(() => expect(put).toHaveBeenCalledWith({ method: 'PUT', body: { content: 'a=2\n' } }))
  })

  it('lists backups and restores one after confirming', async () => {
    const post = vi.fn(() => file)
    serve({ [`POST ${BASE}/restore?path=conf%2Fapp.conf`]: post })
    renderPanel(true)
    fireEvent.click(await screen.findByRole('button', { name: 'app.conf' }))
    fireEvent.click(await screen.findByRole('button', { name: /Restore backup from/ }))
    expect(post).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: 'Restore' }))
    await waitFor(() => expect(post).toHaveBeenCalledWith({ method: 'POST', body: { backupId: 'b1' } }))
  })

  it('shows a server path error inline when creating a file', async () => {
    serve({ [`PUT ${BASE}?path=..%2Fx`]: () => new ApiError(400, 'bad', 'INVALID_PATH') })
    renderPanel(true)
    fireEvent.click(await screen.findByRole('button', { name: 'New file' }))
    fireEvent.change(screen.getByLabelText('File path'), { target: { value: '../x' } })
    fireEvent.click(screen.getByRole('button', { name: 'Create' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('This path is not valid')
  })

  it('is read-only for viewers', async () => {
    serve()
    renderPanel(false)
    fireEvent.click(await screen.findByRole('button', { name: 'app.conf' }))
    expect(await screen.findByLabelText('Contents of conf/app.conf')).toHaveAttribute('readonly')
    expect(screen.queryByRole('button', { name: 'Save' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'New file' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Restore backup/ })).not.toBeInTheDocument()
  })

  it('is read-only for repo files even for operators', async () => {
    serve()
    renderPanel(true)
    fireEvent.click(await screen.findByRole('button', { name: /ro\.txt/ }))
    expect(await screen.findByLabelText('Contents of repo/ro.txt')).toHaveAttribute('readonly')
    expect(screen.queryByRole('button', { name: 'Save' })).not.toBeInTheDocument()
  })

  it('deletes a file after confirming', async () => {
    const del = vi.fn(() => undefined)
    serve({ [`DELETE ${FILE}`]: del })
    renderPanel(true)
    fireEvent.click(await screen.findByRole('button', { name: 'app.conf' }))
    fireEvent.click(await screen.findByRole('button', { name: 'Delete' }))
    fireEvent.click(screen.getAllByRole('button', { name: 'Delete' }).at(-1)!)
    await waitFor(() => expect(del).toHaveBeenCalled())
  })
})

describe('languageFor', () => {
  it('maps extensions, falls back to plaintext', () => {
    expect(languageFor('a/b.json')).toBe('json')
    expect(languageFor('nginx.conf')).toBe('ini')
    expect(languageFor('a.yaml')).toBe('plaintext')
    expect(languageFor('Makefile')).toBe('plaintext')
  })
})

describe('describeConfigError', () => {
  it('explains a refused change while a run is in progress', () => {
    const message = describeConfigError(new ApiError(409, 'busy', 'DEPLOYMENT_IN_PROGRESS'), 'fallback')
    expect(message).toMatch(/run is already in progress/i)
  })
})
