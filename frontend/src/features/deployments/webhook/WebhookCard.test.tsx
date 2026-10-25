import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../../shared/api/client.ts'
import { WebhookCard } from './WebhookCard.tsx'

const apiFetch = vi.fn()
vi.mock('../../../shared/api/client.ts', async (importActual) => ({
  ...(await importActual<typeof import('../../../shared/api/client.ts')>()),
  apiFetch: (...args: unknown[]) => apiFetch(...args),
}))

const PATH = '/hosts/h1/managed-deployments/s1/webhook'
const TOKEN = 'shw_SECRETSECRETSECRET'
const URL_ = `${window.location.origin}/api/webhooks/deploy/${TOKEN}`
const issued = { enabled: true, createdAt: '2026-10-25T10:00:00Z', rotatedAt: null, token: TOKEN, path: `/api/webhooks/deploy/${TOKEN}` }
let status: { enabled: boolean; createdAt: string | null; rotatedAt: string | null } | Error
let writeText: ReturnType<typeof vi.fn>

type Handler = () => unknown
function serve(overrides: Record<string, Handler> = {}) {
  apiFetch.mockImplementation((path: string, init?: { method?: string }) => {
    const key = `${init?.method ?? 'GET'} ${path}`
    if (overrides[key]) {
      const r = overrides[key]()
      return r instanceof Error ? Promise.reject(r) : Promise.resolve(r)
    }
    if (key === `GET ${PATH}`) return status instanceof Error ? Promise.reject(status) : Promise.resolve(status)
    return Promise.resolve(undefined)
  })
}

let client: QueryClient
function renderCard(canOperate = true) {
  client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <WebhookCard hostId="h1" deploymentId="s1" canOperate={canOperate} />
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  status = { enabled: false, createdAt: null, rotatedAt: null }
  writeText = vi.fn().mockResolvedValue(undefined)
  Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })
})
afterEach(() => {
  cleanup()
  apiFetch.mockReset()
})

describe('WebhookCard', () => {
  it('shows a disabled webhook and says what a trigger does', async () => {
    serve()
    renderCard()
    expect(await screen.findByText('Disabled')).toBeInTheDocument()
    expect(screen.getByText(/runs as the system/i)).toBeInTheDocument()
    expect(screen.getByText(/while a deployment is running/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Enable webhook' })).toBeInTheDocument()
  })

  it('enables, reveals the full URL and curl example once, and forgets them afterwards', async () => {
    serve({
      [`PUT ${PATH}`]: () => {
        status = { enabled: true, createdAt: issued.createdAt, rotatedAt: null }
        return issued
      },
    })
    renderCard()
    fireEvent.click(await screen.findByRole('button', { name: 'Enable webhook' }))

    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByLabelText('Webhook URL')).toHaveValue(URL_)
    expect(within(dialog).getByText(`curl -X POST ${URL_}`)).toBeInTheDocument()
    expect(within(dialog).getByText(/shown only once/i)).toBeInTheDocument()
    fireEvent.click(within(dialog).getByRole('button', { name: 'Copy' }))
    await waitFor(() => expect(writeText).toHaveBeenCalledWith(URL_))

    fireEvent.click(within(dialog).getByRole('button', { name: 'Done' }))
    expect(await screen.findByText('Enabled')).toBeInTheDocument()
    expect(document.body.textContent).not.toContain(TOKEN)
    expect(screen.getByRole('button', { name: 'Rotate' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Disable' })).toBeInTheDocument()
  })

  it('shows an enabled webhook after reload without any URL', async () => {
    status = { enabled: true, createdAt: '2026-10-25T10:00:00Z', rotatedAt: '2026-10-26T10:00:00Z' }
    serve()
    renderCard()
    expect(await screen.findByText('Enabled')).toBeInTheDocument()
    expect(screen.queryByLabelText('Webhook URL')).not.toBeInTheDocument()
    expect(document.body.textContent).not.toContain('/api/webhooks/deploy/')
  })

  it('rotates after a confirm that warns the old URL stops working, then reveals the new URL', async () => {
    status = { enabled: true, createdAt: '2026-10-25T10:00:00Z', rotatedAt: null }
    serve({ [`POST ${PATH}/rotate`]: () => ({ ...issued, rotatedAt: '2026-10-26T10:00:00Z' }) })
    renderCard()
    fireEvent.click(await screen.findByRole('button', { name: 'Rotate' }))
    const confirm = await screen.findByRole('dialog')
    expect(within(confirm).getByText(/old url stops working/i)).toBeInTheDocument()
    expect(apiFetch).not.toHaveBeenCalledWith(`${PATH}/rotate`, expect.anything())
    fireEvent.click(within(confirm).getByRole('button', { name: 'Rotate' }))
    await waitFor(() => expect(screen.getByLabelText('Webhook URL')).toHaveValue(URL_))
  })

  it('disables after confirming', async () => {
    status = { enabled: true, createdAt: '2026-10-25T10:00:00Z', rotatedAt: null }
    serve()
    renderCard()
    fireEvent.click(await screen.findByRole('button', { name: 'Disable' }))
    const confirm = await screen.findByRole('dialog')
    fireEvent.click(within(confirm).getByRole('button', { name: 'Disable' }))
    await waitFor(() => expect(apiFetch).toHaveBeenCalledWith(PATH, { method: 'DELETE' }))
  })

  it('reloads the state when the webhook is already enabled', async () => {
    serve({
      [`PUT ${PATH}`]: () => {
        status = { enabled: true, createdAt: issued.createdAt, rotatedAt: null }
        return new ApiError(409, 'x', 'WEBHOOK_ALREADY_ENABLED')
      },
    })
    renderCard()
    fireEvent.click(await screen.findByRole('button', { name: 'Enable webhook' }))
    expect(await screen.findByText(/already enabled/i)).toBeInTheDocument()
    expect(await screen.findByText('Enabled', { exact: true })).toBeInTheDocument()
    expect(apiFetch.mock.calls.filter(([p, i]) => p === PATH && !i)).toHaveLength(2)
  })

  it('shows WEBHOOK_NOT_FOUND on rotate inside the dialog and reloads the state', async () => {
    status = { enabled: true, createdAt: '2026-10-25T10:00:00Z', rotatedAt: null }
    serve({
      [`POST ${PATH}/rotate`]: () => {
        status = { enabled: false, createdAt: null, rotatedAt: null }
        return new ApiError(404, 'x', 'WEBHOOK_NOT_FOUND')
      },
    })
    renderCard()
    fireEvent.click(await screen.findByRole('button', { name: 'Rotate' }))
    const confirm = await screen.findByRole('dialog')
    fireEvent.click(within(confirm).getByRole('button', { name: 'Rotate' }))
    expect(await within(confirm).findByRole('alert')).toHaveTextContent(/not enabled/i)
    expect(await screen.findByText('Disabled')).toBeInTheDocument()
  })

  it.each(['enable', 'rotate'])('keeps no secret in the mutation cache after the %s reveal is closed', async (action) => {
    if (action === 'rotate') status = { enabled: true, createdAt: '2026-10-25T10:00:00Z', rotatedAt: null }
    serve({ [`PUT ${PATH}`]: () => issued, [`POST ${PATH}/rotate`]: () => issued })
    renderCard()
    if (action === 'enable') {
      fireEvent.click(await screen.findByRole('button', { name: 'Enable webhook' }))
    } else {
      fireEvent.click(await screen.findByRole('button', { name: 'Rotate' }))
      fireEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Rotate' }))
    }
    const reveal = (await screen.findByLabelText('Webhook URL')).closest<HTMLElement>('[role="dialog"]')!
    fireEvent.click(within(reveal).getByRole('button', { name: 'Done' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    const held = client
      .getMutationCache()
      .getAll()
      .map((m) => JSON.stringify(m.state.data ?? null))
      .join('')
    expect(held).not.toContain(TOKEN)
  })

  it('renders nothing and requests nothing without operate rights', () => {
    serve()
    const { container } = renderCard(false)
    expect(container).toBeEmptyDOMElement()
    expect(apiFetch).not.toHaveBeenCalled()
  })

  it('renders nothing when the server refuses the status (403)', async () => {
    status = new ApiError(403, 'x', 'FORBIDDEN')
    serve()
    const { container } = renderCard()
    await waitFor(() => expect(apiFetch).toHaveBeenCalled())
    await waitFor(() => expect(container).toBeEmptyDOMElement())
  })

  it('asks to copy manually when the clipboard fails', async () => {
    writeText.mockRejectedValue(new Error('denied'))
    serve({ [`PUT ${PATH}`]: () => issued })
    renderCard()
    fireEvent.click(await screen.findByRole('button', { name: 'Enable webhook' }))
    const dialog = await screen.findByRole('dialog')
    fireEvent.click(within(dialog).getByRole('button', { name: 'Copy' }))
    expect(await within(dialog).findByText(/could not copy/i)).toBeInTheDocument()
  })
})
