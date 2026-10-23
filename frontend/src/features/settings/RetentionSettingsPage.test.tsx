import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, apiFetch } from '../../shared/api/client.ts'
import { RetentionSettingsPage } from './RetentionSettingsPage.tsx'

vi.mock('../../shared/api/client.ts', async (importActual) => ({
  ...(await importActual<typeof import('../../shared/api/client.ts')>()),
  apiFetch: vi.fn(),
}))
const apiFetchMock = vi.mocked(apiFetch)

afterEach(() => {
  cleanup()
  vi.resetAllMocks()
})

const current = { auditLogDays: 90, notificationDays: 30, chatSessionDays: 60 }

function renderPage(put?: () => Promise<unknown>) {
  apiFetchMock.mockImplementation((path: string, options?: { method?: string }) => {
    if ((options?.method ?? 'GET') === 'GET' && path === '/settings/retention') return Promise.resolve(current)
    if (options?.method === 'PUT' && put) return put()
    return Promise.reject(new Error(`unexpected ${path}`))
  })
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <RetentionSettingsPage />
    </QueryClientProvider>,
  )
}

const calls = (method: string) =>
  apiFetchMock.mock.calls.filter(([, o]) => (o as { method?: string } | undefined)?.method === method)

describe('RetentionSettingsPage', () => {
  it('loads the current values into the form', async () => {
    renderPage()
    expect(await screen.findByLabelText('Audit log retention (days)')).toHaveValue(90)
    expect(screen.getByLabelText('Notification retention (days)')).toHaveValue(30)
    expect(screen.getByLabelText('Chat session retention (days)')).toHaveValue(60)
  })

  it('rejects an out-of-range chat session value client-side', async () => {
    renderPage()
    fireEvent.change(await screen.findByLabelText('Chat session retention (days)'), { target: { value: '5000' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    expect(await screen.findByText('Enter a value from 1 to 3650 days.')).toBeInTheDocument()
    expect(calls('PUT')).toHaveLength(0)
  })

  it('explains that the purge runs daily', async () => {
    renderPage()
    expect(await screen.findByText(/once a day/i)).toBeInTheDocument()
  })

  it('rejects an out-of-range value client-side without calling the API', async () => {
    renderPage()
    const audit = await screen.findByLabelText('Audit log retention (days)')
    fireEvent.change(audit, { target: { value: '3651' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    expect(await screen.findByText('Enter a value from 1 to 3650 days.')).toBeInTheDocument()
    expect(calls('PUT')).toHaveLength(0)
  })

  it('rejects a non-integer value client-side', async () => {
    renderPage()
    const notif = await screen.findByLabelText('Notification retention (days)')
    fireEvent.change(notif, { target: { value: '0' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    expect(await screen.findByText('Enter a value from 1 to 3650 days.')).toBeInTheDocument()
    expect(calls('PUT')).toHaveLength(0)
  })

  it('saves the values and shows a success notice', async () => {
    renderPage(() => Promise.resolve({ auditLogDays: 365, notificationDays: 30, chatSessionDays: 60 }))
    const audit = await screen.findByLabelText('Audit log retention (days)')
    fireEvent.change(audit, { target: { value: '365' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    expect(await screen.findByText('Retention settings saved.')).toBeInTheDocument()
    await waitFor(() => expect(calls('PUT')).toHaveLength(1))
    expect(calls('PUT')[0]).toEqual([
      '/settings/retention',
      { method: 'PUT', body: { auditLogDays: 365, notificationDays: 30, chatSessionDays: 60 } },
    ])
  })

  it('shows a server error inline', async () => {
    renderPage(() => Promise.reject(new ApiError(400, 'bad', 'VALIDATION_FAILED', undefined, ['auditLogDays must be at most 3650'])))
    await screen.findByLabelText('Audit log retention (days)')
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('auditLogDays must be at most 3650')
    expect(screen.queryByText('Retention settings saved.')).not.toBeInTheDocument()
  })
})
