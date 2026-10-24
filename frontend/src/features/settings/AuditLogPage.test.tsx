import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, apiFetch } from '../../shared/api/client.ts'
import { AuditLogPage } from './AuditLogPage.tsx'

vi.mock('../../shared/api/client.ts', async (importActual) => ({
  ...(await importActual<typeof import('../../shared/api/client.ts')>()),
  apiFetch: vi.fn(),
}))
const apiFetchMock = vi.mocked(apiFetch)

afterEach(() => {
  cleanup()
  vi.resetAllMocks()
})

const entry = (over: Record<string, unknown> = {}) => ({
  id: 'e1',
  occurredAt: '2026-10-01T10:15:00Z',
  username: 'admin',
  action: 'HOST_CREATE',
  outcome: 'SUCCESS',
  targetType: 'HOST',
  targetId: 'prod-1',
  details: null,
  ...over,
})

function renderPage(respond: (path: string) => Promise<unknown>, url = '/settings/audit-log') {
  apiFetchMock.mockImplementation((path: string) => respond(path))
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[url]}>
        <AuditLogPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

const page = (items: unknown[], p = 0, totalElements = items.length) => ({ items, page: p, size: 20, totalElements })
const lastPath = () => String(apiFetchMock.mock.calls.at(-1)?.[0])

describe('AuditLogPage', () => {
  it('renders rows with readable action, highlighted failure and target', async () => {
    renderPage(() =>
      Promise.resolve(
        page([entry(), entry({ id: 'e2', action: 'LOGIN', outcome: 'FAILURE', username: 'mallory', targetType: null, targetId: null })]),
      ),
    )
    expect(await screen.findByRole('table')).toBeInTheDocument()
    expect(screen.getByText('HOST prod-1')).toBeInTheDocument()
    expect(screen.getByText('mallory')).toBeInTheDocument()
    expect(screen.getByText('Failure')).toBeInTheDocument()
    expect(screen.getByText('Success')).toBeInTheDocument()
  })

  it('requests the first page with size 20', async () => {
    renderPage(() => Promise.resolve(page([entry()])))
    await screen.findByRole('table')
    expect(lastPath()).toBe('/audit-log?page=0&size=20')
  })

  it('applies filters to the query', async () => {
    renderPage(() => Promise.resolve(page([entry()])))
    await screen.findByRole('table')
    fireEvent.change(screen.getByLabelText('Action'), { target: { value: 'LOGIN' } })
    fireEvent.change(screen.getByLabelText('User'), { target: { value: 'bob' } })
    fireEvent.click(screen.getByRole('button', { name: 'Apply' }))
    await waitFor(() => expect(lastPath()).toContain('action=LOGIN'))
    expect(lastPath()).toContain('username=bob')
    expect(lastPath()).toContain('page=0')
  })

  it('sends from and to as inclusive ISO instants', async () => {
    renderPage(() => Promise.resolve(page([entry()])))
    await screen.findByRole('table')
    fireEvent.change(screen.getByLabelText('From'), { target: { value: '2026-10-01' } })
    fireEvent.change(screen.getByLabelText('To'), { target: { value: '2026-10-02' } })
    fireEvent.click(screen.getByRole('button', { name: 'Apply' }))
    await waitFor(() => expect(lastPath()).toContain('from='))
    const params = new URLSearchParams(lastPath().split('?')[1])
    expect(new Date(params.get('from')!).getTime()).toBe(new Date(2026, 9, 1, 0, 0, 0, 0).getTime())
    expect(new Date(params.get('to')!).getTime()).toBe(new Date(2026, 9, 2, 23, 59, 59, 999).getTime())
  })

  it('reads filters from the URL', async () => {
    renderPage(() => Promise.resolve(page([entry()])), '/settings/audit-log?action=LOGIN&username=bob&page=2')
    await screen.findByRole('table')
    expect(screen.getByLabelText('Action')).toHaveValue('LOGIN')
    expect(screen.getByLabelText('User')).toHaveValue('bob')
    expect(lastPath()).toContain('action=LOGIN')
    expect(lastPath()).toContain('page=1')
  })

  it('paginates', async () => {
    renderPage((path) =>
      Promise.resolve(path.includes('page=1') ? page([entry({ id: 'x', action: 'LOGOUT' })], 1, 25) : page([entry()], 0, 25)),
    )
    await screen.findByRole('table')
    expect(screen.getByText('Page 1 of 2')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Previous' })).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: 'Next' }))
    expect(await within(await screen.findByRole('table')).findByText('Logout')).toBeInTheDocument()
    expect(lastPath()).toContain('page=1')
    expect(screen.getByRole('button', { name: 'Next' })).toBeDisabled()
  })

  it('shows an empty state', async () => {
    renderPage(() => Promise.resolve(page([])))
    expect(await screen.findByText('No entries match.')).toBeInTheDocument()
  })

  it('shows an error state', async () => {
    renderPage(() => Promise.reject(new ApiError(500, 'boom')))
    expect(await within(await screen.findByRole('alert')).findByText(/Could not load the audit log/)).toBeInTheDocument()
  })
})
