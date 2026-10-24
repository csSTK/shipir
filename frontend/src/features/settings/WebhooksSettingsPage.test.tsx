import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, apiFetch } from '../../shared/api/client.ts'
import { WebhooksSettingsPage } from './WebhooksSettingsPage.tsx'
import type { Webhook } from './api.ts'

vi.mock('../../shared/api/client.ts', async (importActual) => ({
  ...(await importActual<typeof import('../../shared/api/client.ts')>()),
  apiFetch: vi.fn(),
}))
const apiFetchMock = vi.mocked(apiFetch)

afterEach(() => {
  cleanup()
  vi.resetAllMocks()
})

const hook: Webhook = {
  id: 'w1',
  name: 'Ops chat',
  urlHint: 'https://hooks.example.com/…x9f2',
  eventTypes: ['HOST_DOWN'],
  enabled: true,
  lastDeliveryAt: '2026-10-23T10:00:00Z',
  lastDeliveryStatus: 'SUCCESS',
}

function renderPage(handler?: (path: string, method: string, body: unknown) => unknown) {
  apiFetchMock.mockImplementation((path: string, options?: { method?: string; body?: unknown }) => {
    const method = options?.method ?? 'GET'
    if (method === 'GET' && path === '/notifications/webhooks') return Promise.resolve([hook])
    const result = handler?.(path, method, options?.body)
    return result instanceof Error ? Promise.reject(result) : Promise.resolve(result)
  })
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <WebhooksSettingsPage />
    </QueryClientProvider>,
  )
}

const calls = (method: string) =>
  apiFetchMock.mock.calls.filter(([, o]) => (o as { method?: string } | undefined)?.method === method)

describe('WebhooksSettingsPage', () => {
  it('shows an invalid URL inline', async () => {
    renderPage((_p, method) => (method === 'POST' ? new ApiError(400, 'bad', 'INVALID_WEBHOOK_URL') : undefined))
    await screen.findByText('Ops chat')
    fireEvent.click(screen.getByRole('button', { name: 'Add webhook' }))
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'X' } })
    fireEvent.change(screen.getByLabelText('Webhook URL'), { target: { value: 'ftp://x' } })
    fireEvent.click(screen.getByRole('checkbox', { name: 'Host up' }))
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    expect(await screen.findByText(/absolute http or https/)).toBeInTheDocument()
  })

  it('lists webhooks with a hint only, never a full URL', async () => {
    renderPage()
    expect(await screen.findByText('Ops chat')).toBeInTheDocument()
    expect(screen.getByText(/x9f2/)).toBeInTheDocument()
    expect(screen.getByText(/Host down/)).toBeInTheDocument()
  })

  it('offers every notification type as a webhook event', async () => {
    renderPage()
    await screen.findByText('Ops chat')
    fireEvent.click(screen.getByRole('button', { name: 'Add webhook' }))
    const events = within(screen.getByRole('group', { name: 'Events' }))
    for (const label of [
      'Host down',
      'Host up',
      'Deployment failed',
      'Container crashed',
      'Container unhealthy',
      'Git auto-update failed',
    ]) {
      expect(events.getByRole('checkbox', { name: label })).not.toBeChecked()
    }
    expect(events.getAllByRole('checkbox')).toHaveLength(6)
  })

  it('sends a newly added event type when creating', async () => {
    renderPage(() => ({ ...hook, id: 'w2' }))
    await screen.findByText('Ops chat')
    fireEvent.click(screen.getByRole('button', { name: 'Add webhook' }))
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Pager' } })
    fireEvent.change(screen.getByLabelText('Webhook URL'), { target: { value: 'https://p.example/x' } })
    fireEvent.click(screen.getByRole('checkbox', { name: 'Git auto-update failed' }))
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(calls('POST')).toHaveLength(1))
    expect(calls('POST')[0][1]).toMatchObject({ body: { eventTypes: ['GIT_AUTO_UPDATE_FAILED'] } })
  })

  it('creates a webhook', async () => {
    renderPage(() => ({ ...hook, id: 'w2' }))
    await screen.findByText('Ops chat')
    fireEvent.click(screen.getByRole('button', { name: 'Add webhook' }))
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Pager' } })
    fireEvent.change(screen.getByLabelText('Webhook URL'), { target: { value: 'https://p.example/x' } })
    fireEvent.click(screen.getByRole('checkbox', { name: 'Host down' }))
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(calls('POST')).toHaveLength(1))
    expect(calls('POST')[0]).toEqual([
      '/notifications/webhooks',
      { method: 'POST', body: { name: 'Pager', url: 'https://p.example/x', eventTypes: ['HOST_DOWN'], enabled: true } },
    ])
  })

  it('edits without sending a URL when the field stays empty, and never pre-fills it', async () => {
    renderPage(() => hook)
    await screen.findByText('Ops chat')
    fireEvent.click(screen.getByRole('button', { name: 'Edit Ops chat' }))
    const url = screen.getByLabelText('Webhook URL') as HTMLInputElement
    expect(url.value).toBe('')
    expect(url.type).toBe('password')
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Renamed' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(calls('PUT')).toHaveLength(1))
    const body = (calls('PUT')[0][1] as { body: Record<string, unknown> }).body
    expect(calls('PUT')[0][0]).toBe('/notifications/webhooks/w1')
    expect(body).toEqual({ name: 'Renamed', eventTypes: ['HOST_DOWN'], enabled: true })
    expect('url' in body).toBe(false)
  })

  it('deletes after confirmation', async () => {
    renderPage()
    await screen.findByText('Ops chat')
    fireEvent.click(screen.getByRole('button', { name: 'Delete Ops chat' }))
    const dialog = screen.getByRole('dialog')
    fireEvent.click(within(dialog).getByRole('button', { name: 'Delete' }))
    await waitFor(() => expect(calls('DELETE')).toHaveLength(1))
    expect(calls('DELETE')[0][0]).toBe('/notifications/webhooks/w1')
  })

  it('shows test success and failure inline', async () => {
    let ok = true
    renderPage((path) =>
      path.endsWith('/test') ? (ok ? { ok: true, status: 'SUCCESS' } : { ok: false, status: 'HTTP_500' }) : undefined,
    )
    await screen.findByText('Ops chat')
    fireEvent.click(screen.getByRole('button', { name: 'Send test to Ops chat' }))
    expect(await screen.findByText('Test delivered.')).toBeInTheDocument()
    ok = false
    fireEvent.click(screen.getByRole('button', { name: 'Send test to Ops chat' }))
    expect(await screen.findByText(/Test failed: the receiver answered HTTP 500/)).toBeInTheDocument()
  })

  it('shows a taken name inline', async () => {
    renderPage((_path, method) =>
      method === 'POST' ? new ApiError(409, 'taken', 'WEBHOOK_NAME_TAKEN') : undefined,
    )
    await screen.findByText('Ops chat')
    fireEvent.click(screen.getByRole('button', { name: 'Add webhook' }))
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Ops chat' } })
    fireEvent.change(screen.getByLabelText('Webhook URL'), { target: { value: 'https://p.example/x' } })
    fireEvent.click(screen.getByRole('checkbox', { name: 'Host up' }))
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    expect(await screen.findByText('This name is already in use.')).toBeInTheDocument()
  })

  it('hints at the missing encryption key', async () => {
    renderPage((_path, method) =>
      method === 'POST' ? new ApiError(422, 'no key', 'ENCRYPTION_KEY_MISSING') : undefined,
    )
    await screen.findByText('Ops chat')
    fireEvent.click(screen.getByRole('button', { name: 'Add webhook' }))
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'X' } })
    fireEvent.change(screen.getByLabelText('Webhook URL'), { target: { value: 'https://p.example/x' } })
    fireEvent.click(screen.getByRole('checkbox', { name: 'Host up' }))
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    expect(await screen.findByText(/SHIPIR_ENCRYPTION_KEY/)).toBeInTheDocument()
  })
})
