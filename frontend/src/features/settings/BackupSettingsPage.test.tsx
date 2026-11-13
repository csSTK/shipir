import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { BackupSettingsPage } from './BackupSettingsPage.tsx'

const PASSPHRASE = 'correct horse battery'

const fetchMock = vi.fn()
const clickSpy = vi.fn()

beforeEach(() => {
  vi.stubGlobal('fetch', fetchMock)
  URL.createObjectURL = vi.fn(() => 'blob:backup')
  URL.revokeObjectURL = vi.fn()
  vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
    clickSpy(this.download, this.href)
  })
  document.cookie = 'XSRF-TOKEN=csrf-value; path=/'
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
  fetchMock.mockReset()
  clickSpy.mockReset()
  document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/'
})

function renderPage() {
  const client = new QueryClient({ defaultOptions: { mutations: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <BackupSettingsPage />
    </QueryClientProvider>,
  )
}

function fill(passphrase: string, confirm: string) {
  fireEvent.change(screen.getByLabelText('Passphrase'), { target: { value: passphrase } })
  fireEvent.change(screen.getByLabelText('Confirm passphrase'), { target: { value: confirm } })
}

const submit = () => fireEvent.click(screen.getByRole('button', { name: 'Download backup' }))

/** The backup request is answered by `backup`; the step-up status lookup that precedes it is always a password user. */
function serve(backup: Promise<Response>) {
  fetchMock.mockImplementation((url: string) =>
    url === '/api/auth/step-up'
      ? Promise.resolve(
          new Response(JSON.stringify({ method: 'PASSWORD', totpRequired: false, fresh: false, windowSeconds: 300 }), {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
          }),
        )
      : backup,
  )
}

/** Answers the "Confirm it's you" dialog that follows a valid passphrase. */
async function confirmStepUp(password = 'my-current-password') {
  const dialog = await screen.findByRole('dialog', { name: "Confirm it's you" })
  fireEvent.change(await within(dialog).findByLabelText('Current password'), { target: { value: password } })
  fireEvent.click(within(dialog).getByRole('button', { name: 'Confirm' }))
}

describe('BackupSettingsPage', () => {
  it('explains that the passphrase cannot be recovered and that the encryption key is kept separately', () => {
    renderPage()
    expect(screen.getByText(/passphrase cannot be recovered/i)).toBeInTheDocument()
    expect(screen.getByText(/SHIPIR_ENCRYPTION_KEY/)).toBeInTheDocument()
    expect(screen.getByText(/Backup & restore/)).toBeInTheDocument()
  })

  it('rejects a passphrase shorter than 12 characters without calling the API', () => {
    renderPage()
    fill('short', 'short')
    submit()
    expect(screen.getByRole('alert')).toHaveTextContent('at least 12 characters')
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('rejects a confirmation that does not match without calling the API', () => {
    renderPage()
    fill(PASSPHRASE, 'correct horse battery!')
    submit()
    expect(screen.getByRole('alert')).toHaveTextContent('do not match')
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('posts the passphrase with the CSRF header, downloads the file and clears the fields', async () => {
    serve(Promise.resolve(
      new Response(new TextEncoder().encode('encrypted'), {
        status: 200,
        headers: { 'Content-Disposition': 'attachment; filename="shipir-backup-20261024-120000.tar.gz.enc"' },
      }),
    ))
    renderPage()
    fill(PASSPHRASE, PASSPHRASE)
    submit()
    await confirmStepUp()

    expect(await screen.findByRole('status')).toHaveTextContent('Backup downloaded')
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/backup',
      expect.objectContaining({
        method: 'POST',
        credentials: 'same-origin',
        body: JSON.stringify({ passphrase: PASSPHRASE, stepUp: { password: 'my-current-password' } }),
        headers: expect.objectContaining({ 'X-XSRF-TOKEN': 'csrf-value' }),
      }),
    )
    expect(clickSpy).toHaveBeenCalledWith('shipir-backup-20261024-120000.tar.gz.enc', 'blob:backup')
    await waitFor(() => expect(screen.getByLabelText('Passphrase')).toHaveValue(''))
    expect(screen.getByLabelText('Confirm passphrase')).toHaveValue('')
  })

  it('asks for the password before exporting and sends nothing if the dialog is cancelled', async () => {
    serve(Promise.resolve(new Response('x', { status: 200 })))
    renderPage()
    fill(PASSPHRASE, PASSPHRASE)
    submit()
    const dialog = await screen.findByRole('dialog', { name: "Confirm it's you" })
    expect(fetchMock).not.toHaveBeenCalledWith('/api/backup', expect.anything())
    fireEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(fetchMock).not.toHaveBeenCalledWith('/api/backup', expect.anything())
    expect(screen.getByLabelText('Passphrase')).toHaveValue(PASSPHRASE)
  })

  it('keeps the dialog open with a message when the password is wrong', async () => {
    serve(Promise.resolve(new Response(JSON.stringify({ code: 'STEP_UP_INVALID', message: 'x' }), { status: 403 })))
    renderPage()
    fill(PASSPHRASE, PASSPHRASE)
    submit()
    await confirmStepUp('wrong')
    const dialog = await screen.findByRole('dialog', { name: "Confirm it's you" })
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('password or code is not correct')
    expect(clickSpy).not.toHaveBeenCalled()
  })

  it('shows the pending state at once and disables the button while the backup is created', async () => {
    serve(new Promise(() => {}))
    renderPage()
    fill(PASSPHRASE, PASSPHRASE)
    submit()
    await confirmStepUp()

    // the page sits behind the modal confirmation, so it is hidden from the accessibility tree
    const pending = await screen.findByRole('button', { name: 'Creating backup…', hidden: true })
    expect(pending).toBeDisabled()
  })

  it('shows a 400 validation error inline and keeps the passphrase fields', async () => {
    serve(Promise.resolve(
      new Response(
        JSON.stringify({ code: 'VALIDATION_ERROR', message: 'Invalid', details: ['passphrase: size must be between 12 and 128'] }),
        { status: 400 },
      ),
    ))
    renderPage()
    fill(PASSPHRASE, PASSPHRASE)
    submit()
    await confirmStepUp()

    expect(await screen.findByRole('alert')).toHaveTextContent('passphrase: size must be between 12 and 128')
    expect(screen.getByLabelText('Passphrase')).toHaveValue(PASSPHRASE)
    expect(clickSpy).not.toHaveBeenCalled()
  })

  it('shows a generic message for a forbidden response and never the raw body', async () => {
    serve(Promise.resolve(new Response('<html>secret stack trace</html>', { status: 403 })))
    renderPage()
    fill(PASSPHRASE, PASSPHRASE)
    submit()
    await confirmStepUp()

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('You are not allowed to create backups.')
    expect(alert).not.toHaveTextContent('stack trace')
  })
})
