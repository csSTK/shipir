import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../../shared/api/client.ts'
import { TokensSection } from './TokensSection.tsx'

const apiFetch = vi.fn()
vi.mock('../../../shared/api/client.ts', async (importActual) => ({
  ...(await importActual<typeof import('../../../shared/api/client.ts')>()),
  apiFetch: (...args: unknown[]) => apiFetch(...args),
}))

const SECRET = 'shp_abcd1234SECRETSECRETSECRET'
const TOKEN = { id: 't1', name: 'CI', prefix: 'shp_abcd', createdAt: '2026-10-01T10:00:00Z', expiresAt: '2027-10-01T10:00:00Z', lastUsedAt: null }
let tokens: unknown[]
let writeText: ReturnType<typeof vi.fn>

type Handler = (init?: { method?: string; body?: unknown }) => unknown
function serve(overrides: Record<string, Handler> = {}) {
  apiFetch.mockImplementation((path: string, init?: { method?: string; body?: unknown }) => {
    const key = `${init?.method ?? 'GET'} ${path}`
    if (overrides[key]) {
      const r = overrides[key](init)
      return r instanceof Error ? Promise.reject(r) : Promise.resolve(r)
    }
    if (key === 'GET /account/tokens') return Promise.resolve(tokens)
    return Promise.resolve(undefined)
  })
}

function renderSection() {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <TokensSection />
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  tokens = [TOKEN]
  writeText = vi.fn().mockResolvedValue(undefined)
  Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })
})
afterEach(() => {
  cleanup()
  apiFetch.mockReset()
})

const created = { id: 't2', name: 'Deploy script', prefix: 'shp_abcd', createdAt: '2026-10-25T10:00:00Z', expiresAt: '2027-10-25T10:00:00Z', token: SECRET }

async function openCreate() {
  fireEvent.click(await screen.findByRole('button', { name: 'Create token' }))
  return screen.findByRole('dialog')
}

describe('TokensSection', () => {
  it('lists tokens with prefix, dates and never used, without any secret', async () => {
    serve()
    renderSection()
    expect(await screen.findByText('CI')).toBeInTheDocument()
    expect(screen.getByText('shp_abcd…')).toBeInTheDocument()
    expect(screen.getByText('Never')).toBeInTheDocument()
    expect(document.body.textContent).not.toContain(SECRET)
  })

  it('shows an empty state without tokens', async () => {
    tokens = []
    serve()
    renderSection()
    expect(await screen.findByText(/no api tokens/i)).toBeInTheDocument()
    // The empty state carries the create action like other empty states.
    expect(screen.getAllByRole('button', { name: 'Create token' })).toHaveLength(1)
    expect(document.querySelector('.ui-empty')).toContainElement(screen.getByRole('button', { name: 'Create token' }))
  })

  it('explains how to use a token with a Bearer header', async () => {
    serve()
    renderSection()
    expect(await screen.findByText(/Authorization: Bearer/)).toBeInTheDocument()
  })

  it('needs a name before creating', async () => {
    serve()
    renderSection()
    const dialog = await openCreate()
    expect(within(dialog).getByRole('button', { name: 'Create' })).toBeDisabled()
  })

  it('omits the expiry for the default (longest allowed) and sends a date for a shorter choice', async () => {
    serve({ 'POST /account/tokens': () => created })
    renderSection()
    let dialog = await openCreate()
    fireEvent.change(within(dialog).getByLabelText('Name'), { target: { value: 'Deploy script' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Create' }))
    await waitFor(() => expect(apiFetch).toHaveBeenCalledWith('/account/tokens', { method: 'POST', body: { name: 'Deploy script' } }))
    cleanup()

    serve({ 'POST /account/tokens': () => created })
    renderSection()
    dialog = await openCreate()
    fireEvent.change(within(dialog).getByLabelText('Name'), { target: { value: 'Short' } })
    fireEvent.change(within(dialog).getByLabelText('Expires'), { target: { value: '30' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Create' }))
    await waitFor(() => {
      const call = apiFetch.mock.calls.find(([p, i]) => p === '/account/tokens' && i?.method === 'POST' && i.body.name === 'Short')
      expect(call).toBeDefined()
      const days = (Date.parse(call![1].body.expiresAt) - Date.now()) / 86_400_000
      expect(days).toBeGreaterThan(29.9)
      expect(days).toBeLessThan(30.1)
    })
  })

  it('reveals the token once with a warning, copies it, and forgets it when closed', async () => {
    serve({ 'POST /account/tokens': () => created })
    renderSection()
    const dialog = await openCreate()
    fireEvent.change(within(dialog).getByLabelText('Name'), { target: { value: 'Deploy script' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Create' }))

    const reveal = await screen.findByRole('dialog')
    expect(within(reveal).getByLabelText('Token')).toHaveValue(SECRET)
    expect(within(reveal).getByText(/shown only once/i)).toBeInTheDocument()
    expect(within(reveal).getByText(/your permissions/i)).toBeInTheDocument()
    fireEvent.click(within(reveal).getByRole('button', { name: 'Copy' }))
    await waitFor(() => expect(writeText).toHaveBeenCalledWith(SECRET))

    fireEvent.click(within(reveal).getByRole('button', { name: 'Done' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(document.body.textContent).not.toContain(SECRET)
  })

  it('shows an invalid expiry inline and keeps the dialog open', async () => {
    serve({ 'POST /account/tokens': () => new ApiError(400, 'x', 'INVALID_EXPIRY') })
    renderSection()
    const dialog = await openCreate()
    fireEvent.change(within(dialog).getByLabelText('Name'), { target: { value: 'x' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Create' }))
    expect(await within(dialog).findByRole('alert')).toHaveTextContent(/expiry/i)
  })

  it('revokes a token after confirming', async () => {
    serve()
    renderSection()
    fireEvent.click(await screen.findByRole('button', { name: 'Revoke token CI' }))
    const dialog = await screen.findByRole('dialog')
    expect(apiFetch).not.toHaveBeenCalledWith('/account/tokens/t1', expect.anything())
    fireEvent.click(within(dialog).getByRole('button', { name: 'Revoke' }))
    await waitFor(() => expect(apiFetch).toHaveBeenCalledWith('/account/tokens/t1', { method: 'DELETE' }))
  })
})
