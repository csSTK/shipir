import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../../shared/api/client.ts'
import { CertificatesSection } from './CertificatesSection.tsx'

const apiFetch = vi.fn()
vi.mock('../../../shared/api/client.ts', async (importActual) => ({
  ...(await importActual<typeof import('../../../shared/api/client.ts')>()),
  apiFetch: (...args: unknown[]) => apiFetch(...args),
}))

const DAY = 24 * 60 * 60 * 1000
const inDays = (n: number) => new Date(Date.now() + n * DAY).toISOString()

const wild = { id: 'c1', name: 'wildcard', domains: ['*.example.com', 'example.com'], notAfter: inDays(200), usedByRoutes: ['app.example.com'] }
const soon = { id: 'c2', name: 'shop', domains: ['shop.example.com'], notAfter: inDays(10), usedByRoutes: [] }
const gone = { id: 'c3', name: 'old', domains: ['old.example.com'], notAfter: inDays(-3), usedByRoutes: [] }

let list: unknown[]
beforeEach(() => {
  list = [wild, soon]
  apiFetch.mockImplementation((path: string, options?: { method?: string }) => {
    if (path === '/hosts/h1/proxy/certificates' && !options?.method) return Promise.resolve(list)
    return Promise.resolve(null)
  })
})
afterEach(() => {
  cleanup()
  apiFetch.mockReset()
})

let queryClient: QueryClient

function renderSection(isAdmin: boolean) {
  queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={queryClient}>
      <CertificatesSection hostId="h1" isAdmin={isAdmin} />
    </QueryClientProvider>,
  )
}

function openMenu(trigger: HTMLElement) {
  trigger.focus()
  fireEvent.keyDown(trigger, { key: 'Enter' })
}

function failNextWrite(error: Error) {
  apiFetch.mockImplementation((_path: string, options?: { method?: string }) => {
    if (options?.method) return Promise.reject(error)
    return Promise.resolve(list)
  })
}

async function openUpload() {
  fireEvent.click(await screen.findByRole('button', { name: 'Upload certificate' }))
  return screen.findByRole('dialog')
}

function fillUpload(dialog: HTMLElement, name = 'wildcard') {
  fireEvent.change(within(dialog).getByLabelText('Name'), { target: { value: name } })
  fireEvent.change(within(dialog).getByLabelText('Certificate chain'), { target: { value: '-----BEGIN CERTIFICATE-----\nAAA\n-----END CERTIFICATE-----' } })
  fireEvent.change(within(dialog).getByLabelText('Private key'), { target: { value: '-----BEGIN PRIVATE KEY-----\nBBB\n-----END PRIVATE KEY-----' } })
}

describe('CertificatesSection', () => {
  it('lists name, domains, expiry and the routes using each certificate', async () => {
    renderSection(true)
    const table = await screen.findByRole('table', { name: 'certificates' })
    expect(within(table).getByText('wildcard')).toBeInTheDocument()
    expect(within(table).getByText('*.example.com, example.com')).toBeInTheDocument()
    expect(within(table).getByText('app.example.com')).toBeInTheDocument()
    expect(within(table).getByText('shop')).toBeInTheDocument()
  })

  it('warns when a certificate expires within 30 days and when it is expired', async () => {
    list = [wild, soon, gone]
    renderSection(true)
    expect(await screen.findByText('10 days left')).toBeInTheDocument()
    expect(screen.getByText('Expired')).toBeInTheDocument()
    expect(screen.queryByText('200 days left')).not.toBeInTheDocument()
  })

  it('explains uploading when there are no certificates', async () => {
    list = []
    renderSection(true)
    expect(await screen.findByText('No certificates yet')).toBeInTheDocument()
    expect(screen.getByText(/certificate you bought or created yourself/i)).toBeInTheDocument()
  })

  it('uses neutral wording for non-admins with an empty list', async () => {
    list = []
    renderSection(false)
    expect(await screen.findByText('No certificates yet')).toBeInTheDocument()
    expect(screen.getByText(/no certificates to show/i)).toBeInTheDocument()
  })

  it('uploads a certificate with name, chain and key and clears the form', async () => {
    renderSection(true)
    const dialog = await openUpload()
    fillUpload(dialog)
    fireEvent.click(within(dialog).getByRole('button', { name: 'Upload' }))
    await waitFor(() =>
      expect(apiFetch).toHaveBeenCalledWith('/hosts/h1/proxy/certificates', {
        method: 'POST',
        body: {
          name: 'wildcard',
          certPem: '-----BEGIN CERTIFICATE-----\nAAA\n-----END CERTIFICATE-----',
          keyPem: '-----BEGIN PRIVATE KEY-----\nBBB\n-----END PRIVATE KEY-----',
        },
      }),
    )
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    // reopening shows an empty key field
    const again = await openUpload()
    expect(within(again).getByLabelText('Private key')).toHaveValue('')
  })

  it('never prefills the key field and turns autocomplete off', async () => {
    renderSection(true)
    const dialog = await openUpload()
    const key = within(dialog).getByLabelText('Private key')
    expect(key).toHaveAttribute('autocomplete', 'off')
    expect(key).toHaveValue('')
  })

  it('keeps no private key in the mutation cache after a failed or a successful submit', async () => {
    renderSection(true)
    const dialog = await openUpload()
    fillUpload(dialog)
    failNextWrite(new ApiError(400, 'x', 'KEY_MISMATCH'))
    fireEvent.click(within(dialog).getByRole('button', { name: 'Upload' }))
    await within(dialog).findByText(/key does not belong/i)
    expect(JSON.stringify(queryClient.getMutationCache().getAll().map((m) => m.state.variables))).not.toContain('PRIVATE KEY')
    expect(within(dialog).getByLabelText('Private key')).toHaveValue('')

    apiFetch.mockImplementation(() => Promise.resolve(list))
    fireEvent.change(within(dialog).getByLabelText('Private key'), { target: { value: '-----BEGIN PRIVATE KEY-----\nBBB\n-----END PRIVATE KEY-----' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Upload' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(JSON.stringify(queryClient.getMutationCache().getAll().map((m) => m.state.variables))).not.toContain('PRIVATE KEY')
  })

  it('rejects an oversized key file inline without reading it', async () => {
    renderSection(true)
    const dialog = await openUpload()
    const file = new File(['x'.repeat(16 * 1024 + 1)], 'big.key')
    const text = vi.spyOn(file, 'text')
    fireEvent.change(within(dialog).getByLabelText('Choose key file'), { target: { files: [file] } })
    expect(await within(dialog).findByText(/file is too large/i)).toBeInTheDocument()
    expect(text).not.toHaveBeenCalled()
    expect(within(dialog).getByLabelText('Private key')).toHaveValue('')
  })

  it('shows an inline error when a file cannot be read', async () => {
    renderSection(true)
    const dialog = await openUpload()
    const file = new File(['x'], 'a.pem')
    vi.spyOn(file, 'text').mockRejectedValue(new Error('io'))
    fireEvent.change(within(dialog).getByLabelText('Choose certificate file'), { target: { files: [file] } })
    expect(await within(dialog).findByText(/could not read this file/i)).toBeInTheDocument()
  })

  it('shows a mismatched key inline at the key field and clears the key', async () => {
    renderSection(true)
    const dialog = await openUpload()
    fillUpload(dialog)
    failNextWrite(new ApiError(400, 'x', 'KEY_MISMATCH'))
    fireEvent.click(within(dialog).getByRole('button', { name: 'Upload' }))
    const alert = await within(dialog).findByText(/key does not belong to this certificate/i)
    expect(alert).toBeInTheDocument()
    expect(within(dialog).getByLabelText('Private key')).toHaveValue('')
    expect(within(dialog).getByLabelText('Certificate chain')).not.toHaveValue('')
  })

  it.each([
    ['KEY_ENCRYPTED', /protected by a password/i],
    ['CERTIFICATE_EXPIRED', /has expired/i],
    ['CERT_NAME_TAKEN', /name is already used/i],
  ])('maps %s to a plain-language message', async (code, text) => {
    renderSection(true)
    const dialog = await openUpload()
    fillUpload(dialog)
    failNextWrite(new ApiError(code === 'CERT_NAME_TAKEN' ? 409 : 400, 'x', code))
    fireEvent.click(within(dialog).getByRole('button', { name: 'Upload' }))
    expect(await within(dialog).findByText(text)).toBeInTheDocument()
  })

  it('renews with chain and key only', async () => {
    renderSection(true)
    fireEvent.click(await screen.findByRole('button', { name: 'Renew wildcard' }))
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).queryByLabelText('Name')).not.toBeInTheDocument()
    fireEvent.change(within(dialog).getByLabelText('Certificate chain'), { target: { value: 'CHAIN' } })
    fireEvent.change(within(dialog).getByLabelText('Private key'), { target: { value: 'KEY' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Renew' }))
    await waitFor(() =>
      expect(apiFetch).toHaveBeenCalledWith('/hosts/h1/proxy/certificates/c1', { method: 'PUT', body: { certPem: 'CHAIN', keyPem: 'KEY' } }),
    )
  })

  it('reads a chosen file into the field', async () => {
    renderSection(true)
    const dialog = await openUpload()
    const file = new File(['PEMDATA'], 'chain.pem', { type: 'application/x-pem-file' })
    fireEvent.change(within(dialog).getByLabelText('Choose certificate file'), { target: { files: [file] } })
    await waitFor(() => expect(within(dialog).getByLabelText('Certificate chain')).toHaveValue('PEMDATA'))
  })

  it('deletes an unused certificate after confirmation', async () => {
    renderSection(true)
    openMenu(await screen.findByRole('button', { name: 'More actions for shop' }))
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Delete' }))
    const dialog = await screen.findByRole('dialog')
    fireEvent.click(within(dialog).getByRole('button', { name: 'Delete' }))
    await waitFor(() => expect(apiFetch).toHaveBeenCalledWith('/hosts/h1/proxy/certificates/c2', { method: 'DELETE' }))
  })

  it('names the routes that still use a certificate when deleting it is refused', async () => {
    renderSection(true)
    openMenu(await screen.findByRole('button', { name: 'More actions for wildcard' }))
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Delete' }))
    const dialog = await screen.findByRole('dialog')
    const err = new ApiError(409, 'x', 'CERTIFICATE_IN_USE', undefined, ['app.example.com'])
    failNextWrite(err)
    fireEvent.click(within(dialog).getByRole('button', { name: 'Delete' }))
    const alert = await within(dialog).findByRole('alert')
    expect(alert).toHaveTextContent(/still used by app\.example\.com/i)
  })

  it('is read-only for non-admins', async () => {
    renderSection(false)
    await screen.findByRole('table', { name: 'certificates' })
    expect(screen.queryByRole('button', { name: 'Upload certificate' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Renew wildcard' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'More actions for wildcard' })).not.toBeInTheDocument()
  })

  it('shows a retry when the list cannot be loaded', async () => {
    apiFetch.mockImplementation(() => Promise.reject(new Error('boom')))
    renderSection(true)
    expect(await screen.findByText(/Could not load the certificates/i)).toBeInTheDocument()
  })
})
