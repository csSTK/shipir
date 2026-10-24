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

let handlers: { onMessage: (body: string) => void; onState?: (s: 'connected') => void } | null = null
vi.mock('../../shared/realtime/stomp.ts', () => ({
  subscribeToTopic: (_dest: string, h: typeof handlers) => {
    handlers = h
    return () => {}
  },
}))

afterEach(() => {
  cleanup()
  apiFetch.mockReset()
  handlers = null
})

const images = [
  { id: 'sha256:aaaaaaaaaaaaaaaaaaaa', repoTags: ['nginx:latest'], size: 1, created: '2026-10-01T10:00:00Z', inUse: true, updateAvailable: true },
  { id: 'sha256:bbbbbbbbbbbbbbbbbbbb', repoTags: ['redis:7'], size: 1, created: '2026-09-01T10:00:00Z', inUse: false, updateAvailable: false },
]

function serve(role: string, pull?: () => Promise<unknown>) {
  apiFetch.mockImplementation((path: string, init?: { method?: string }) => {
    if (path === '/auth/me') return Promise.resolve({ username: 'u', role, setupRequired: false })
    if (path === '/hosts/h1/images/pull' && init?.method === 'POST') return pull ? pull() : Promise.resolve({ runId: 'run-1' })
    if (path === '/hosts/h1/images') return Promise.resolve(images)
    return Promise.resolve([])
  })
}

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

const frame = (f: object) => handlers!.onMessage(JSON.stringify(f))

describe('image updates and pull dialog', () => {
  it('shows the badge only for flagged images; viewers get no pull controls', async () => {
    serve('VIEWER')
    renderPage()
    await screen.findByText('redis:7')
    expect(screen.getAllByText('Update available')).toHaveLength(1)
    expect(screen.queryByRole('button', { name: /Pull/ })).not.toBeInTheDocument()
  })

  it('opens the dialog prefilled from the badge, posts the reference and streams progress', async () => {
    serve('OPERATOR')
    renderPage()
    fireEvent.click(await screen.findByRole('button', { name: 'Pull & update nginx:latest' }))
    const dialog = screen.getByRole('dialog', { name: 'Pull image' })
    expect(within(dialog).getByLabelText('Image reference')).toHaveValue('nginx:latest')
    fireEvent.click(within(dialog).getByRole('button', { name: 'Pull' }))
    expect(await within(dialog).findByRole('log')).toBeInTheDocument()
    expect(apiFetch).toHaveBeenCalledWith('/hosts/h1/images/pull', expect.objectContaining({ method: 'POST', body: { reference: 'nginx:latest' } }))
    frame({ seq: 1, type: 'LINE', line: 'Downloading layer abc' })
    frame({ seq: 2, type: 'STATUS', status: 'DONE' })
    expect(await within(dialog).findByText('Downloading layer abc')).toBeInTheDocument()
    expect(await within(dialog).findByText('Succeeded')).toBeInTheDocument()
  })

  it('shows a failed pull inline', async () => {
    serve('ADMIN')
    renderPage()
    fireEvent.click(await screen.findByRole('button', { name: 'Pull image' }))
    fireEvent.change(screen.getByLabelText('Image reference'), { target: { value: 'nginx:1' } })
    fireEvent.click(screen.getByRole('button', { name: 'Pull' }))
    await screen.findByRole('log')
    frame({ seq: 1, type: 'LINE', line: 'Pull failed (NOT_FOUND)' })
    frame({ seq: 2, type: 'STATUS', status: 'FAILED' })
    expect(await screen.findByText('Pull failed (NOT_FOUND)')).toBeInTheDocument()
    expect(await screen.findByText('Failed')).toBeInTheDocument()
  })

  it('maps INVALID_IMAGE_REFERENCE and keeps the form', async () => {
    serve('ADMIN', () => Promise.reject(new ApiError(400, 'x', 'INVALID_IMAGE_REFERENCE')))
    renderPage()
    fireEvent.click(await screen.findByRole('button', { name: 'Pull image' }))
    expect(screen.getByRole('button', { name: 'Pull' })).toBeDisabled()
    fireEvent.change(screen.getByLabelText('Image reference'), { target: { value: 'Bad Ref' } })
    fireEvent.click(screen.getByRole('button', { name: 'Pull' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('not a valid image reference')
    expect(screen.getByLabelText('Image reference')).toHaveValue('Bad Ref')
  })
})
