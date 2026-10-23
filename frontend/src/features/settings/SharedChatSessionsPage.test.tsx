import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, apiFetch } from '../../shared/api/client.ts'
import { SharedChatSessionsPage } from './SharedChatSessionsPage.tsx'

vi.mock('../../shared/api/client.ts', async (importActual) => ({
  ...(await importActual<typeof import('../../shared/api/client.ts')>()),
  apiFetch: vi.fn(),
}))
const apiFetchMock = vi.mocked(apiFetch)

afterEach(() => {
  cleanup()
  vi.resetAllMocks()
})

const shared = (over: Record<string, unknown> = {}) => ({
  id: 's1',
  title: 'Why is web down',
  sharedWithAdmin: true,
  createdAt: '2026-10-22T09:00:00Z',
  updatedAt: '2026-10-22T10:00:00Z',
  ownerUsername: 'bob',
  ...over,
})

const page = (items: unknown[], p = 0, totalElements = items.length) => ({ items, page: p, size: 20, totalElements })

const message = (id: string, over: Record<string, unknown> = {}) => ({
  id,
  seq: 1,
  role: 'USER',
  content: 'hello',
  toolCalls: null,
  toolCallId: null,
  status: null,
  createdAt: '2026-10-22T09:00:00Z',
  ...over,
})

const detail = (messages: unknown[] = [message('m1')]) => ({ session: shared(), messages })

const pendingAction = message('m2', {
  seq: 2,
  role: 'TOOL',
  content: null,
  status: 'PENDING',
  toolCalls: [{ id: 'c1', name: 'restart_container', arguments: { name: 'web' }, description: 'Restart web' }],
})

function renderPage(respond: (path: string) => Promise<unknown>, url = '/settings/ai/shared') {
  apiFetchMock.mockImplementation((path: string) => respond(path))
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[url]}>
        <SharedChatSessionsPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('SharedChatSessionsPage', () => {
  it('lists shared sessions with owner and title', async () => {
    renderPage(() => Promise.resolve(page([shared(), shared({ id: 's2', title: 'Disk full', ownerUsername: 'carol' })])))
    expect(await screen.findByRole('button', { name: /Why is web down/ })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Disk full/ })).toHaveTextContent('carol')
  })

  it('opens a read-only transcript without action buttons', async () => {
    renderPage((path) =>
      Promise.resolve(path.startsWith('/ai/shared-sessions/s1') ? detail([message('m1'), pendingAction]) : page([shared()])),
    )
    fireEvent.click(await screen.findByRole('button', { name: /Why is web down/ }))
    const log = await screen.findByRole('log', { name: 'Conversation' })
    expect(within(log).getByText('hello')).toBeInTheDocument()
    expect(within(log).getByRole('region', { name: /restart container/ })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /confirm|reject/i })).not.toBeInTheDocument()
  })

  it('shows an empty state when nothing is shared', async () => {
    renderPage(() => Promise.resolve(page([])))
    expect(await screen.findByText('No shared chats.')).toBeInTheDocument()
  })

  it('shows an error state when the list fails', async () => {
    renderPage(() => Promise.reject(new ApiError(500, 'boom')))
    expect(await within(await screen.findByRole('alert')).findByText(/Could not load shared chats/)).toBeInTheDocument()
  })

  it('opens the session named in the URL', async () => {
    renderPage(
      (path) => (path.startsWith('/ai/shared-sessions/s1') ? Promise.resolve(detail()) : Promise.resolve(page([shared()]))),
      '/settings/ai/shared?session=s1',
    )
    expect(await screen.findByRole('log', { name: 'Conversation' })).toBeInTheDocument()
    await waitFor(() => expect(screen.getByRole('button', { name: /Why is web down/ })).toHaveAttribute('aria-current', 'true'))
  })

  it('shows an error when the transcript cannot be loaded', async () => {
    renderPage(
      (path) => (path.startsWith('/ai/shared-sessions/s1') ? Promise.reject(new ApiError(404, 'gone')) : Promise.resolve(page([shared()]))),
      '/settings/ai/shared?session=s1',
    )
    expect(await within(await screen.findByRole('alert')).findByText(/Could not load this chat/)).toBeInTheDocument()
  })
})
