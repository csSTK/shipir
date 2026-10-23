import { QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { queryClient } from '../../app/queryClient.ts'
import { apiFetch } from '../../shared/api/client.ts'
import { NotificationsPage } from './NotificationsPage.tsx'
import { useUnreadCount } from './api.ts'

vi.mock('../../shared/api/client.ts', async (importActual) => ({
  ...(await importActual<typeof import('../../shared/api/client.ts')>()),
  apiFetch: vi.fn(),
}))
const apiFetchMock = vi.mocked(apiFetch)

type N = {
  id: string
  type: 'HOST_DOWN' | 'HOST_UP'
  hostId: string | null
  hostName: string
  message: string
  read: boolean
  createdAt: string
}
const down: N = {
  id: 'n1', type: 'HOST_DOWN', hostId: 'h1', hostName: 'Staging',
  message: 'Staging is unreachable', read: false, createdAt: new Date().toISOString(),
}
const up: N = {
  id: 'n2', type: 'HOST_UP', hostId: null, hostName: 'Old box',
  message: 'Old box is back', read: true, createdAt: new Date(Date.now() - 3_600_000).toISOString(),
}

let items: N[]
let calls: string[]

beforeEach(() => {
  queryClient.clear()
  items = [down, up]
  calls = []
  apiFetchMock.mockImplementation(async (path, options) => {
    const method = options?.method ?? 'GET'
    calls.push(`${method} ${path}`)
    if (method === 'POST' && path === '/notifications/read-all') {
      items = items.map((n) => ({ ...n, read: true }))
      return undefined as never
    }
    if (method === 'POST' && path.endsWith('/read')) {
      const id = path.split('/')[2]
      items = items.map((n) => (n.id === id ? { ...n, read: true } : n))
      return undefined as never
    }
    if (path === '/notifications/unread-count') {
      return { count: items.filter((n) => !n.read).length } as never
    }
    if (path.startsWith('/notifications')) {
      const unreadOnly = path.includes('unreadOnly=true')
      const content = unreadOnly ? items.filter((n) => !n.read) : items
      return { content, totalElements: content.length } as never
    }
    throw new Error(`unexpected ${method} ${path}`)
  })
})
afterEach(() => {
  cleanup()
  vi.resetAllMocks()
})

function Badge() {
  const { data } = useUnreadCount()
  return <p data-testid="badge">{data?.count ?? '-'}</p>
}

function renderPage() {
  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <Badge />
        <NotificationsPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('NotificationsPage', () => {
  it('lists notifications, linking only hosts that still exist', async () => {
    renderPage()
    expect(await screen.findByText('Staging is unreachable')).toBeInTheDocument()
    expect(screen.getByText('Old box is back')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Staging' })).toHaveAttribute('href', '/environments/h1')
    expect(screen.queryByRole('link', { name: 'Old box' })).toBeNull()
    expect(screen.getByText('Down')).toBeInTheDocument()
    expect(screen.getByText('Up')).toBeInTheDocument()
  })

  it('filters to unread', async () => {
    renderPage()
    await screen.findByText('Old box is back')
    fireEvent.click(screen.getByRole('checkbox', { name: 'Unread only' }))
    await waitFor(() => expect(screen.queryByText('Old box is back')).toBeNull())
    expect(await screen.findByText('Staging is unreachable')).toBeInTheDocument()
  })

  it('marks one as read and lowers the badge', async () => {
    renderPage()
    await waitFor(() => expect(screen.getByTestId('badge')).toHaveTextContent('1'))
    fireEvent.click(await screen.findByRole('button', { name: 'Mark as read' }))
    await waitFor(() => expect(screen.getByTestId('badge')).toHaveTextContent('0'))
    expect(calls).toContain('POST /notifications/n1/read')
    expect(screen.queryByRole('button', { name: 'Mark as read' })).toBeNull()
  })

  it('marks all as read', async () => {
    renderPage()
    await waitFor(() => expect(screen.getByTestId('badge')).toHaveTextContent('1'))
    fireEvent.click(await screen.findByRole('button', { name: 'Mark all as read' }))
    await waitFor(() => expect(screen.getByTestId('badge')).toHaveTextContent('0'))
    expect(calls).toContain('POST /notifications/read-all')
  })

  it('shows the empty state', async () => {
    items = []
    renderPage()
    expect(await screen.findByText('No notifications yet.')).toBeInTheDocument()
  })

  it('shows an error state', async () => {
    apiFetchMock.mockRejectedValue(new Error('boom'))
    renderPage()
    expect(await screen.findByRole('alert', {}, { timeout: 5000 })).toHaveTextContent('Could not load notifications.')
  }, 10_000)
})
