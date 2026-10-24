import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { QueryClientProvider } from '@tanstack/react-query'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { queryClient } from '../../app/queryClient.ts'
import { ApiError, apiFetch } from '../../shared/api/client.ts'
import { AccountPage } from './AccountPage.tsx'

vi.mock('../../shared/api/client.ts', async (importActual) => ({
  ...(await importActual<typeof import('../../shared/api/client.ts')>()),
  apiFetch: vi.fn(),
}))
const apiFetchMock = vi.mocked(apiFetch)

function route(extra: Record<string, () => unknown> = {}) {
  apiFetchMock.mockImplementation(((path: string, init?: { method?: string }) => {
    const key = `${init?.method ?? 'GET'} ${path}`
    if (extra[key]) return Promise.resolve().then(extra[key])
    if (key === 'GET /notifications/preferences') return Promise.resolve([])
    return Promise.resolve(undefined)
  }) as typeof apiFetch)
}
const renderPage = (twoFactorEnabled: boolean) =>
  render(
    <QueryClientProvider client={queryClient}>
      <AccountPage user={{ username: 'pat', role: 'OPERATOR', twoFactorEnabled }} />
    </QueryClientProvider>,
  )

beforeEach(() => queryClient.clear())
afterEach(() => {
  cleanup()
  vi.resetAllMocks()
})

describe('AccountPage two-factor section', () => {
  it('enrols: shows QR + secret, confirms, shows recovery codes with download and clears the secret', async () => {
    route({
      'POST /auth/2fa/setup': () => ({ secret: 'SECRETSECRET', otpauthUri: 'otpauth://totp/Shipir:pat?secret=SECRETSECRET' }),
      'POST /auth/2fa/confirm': () => ({ recoveryCodes: ['AAAAA-11111', 'BBBBB-22222'] }),
    })
    renderPage(false)
    fireEvent.click(screen.getByRole('button', { name: /enable two-factor/i }))
    expect(await screen.findByText('SECRETSECRET')).toBeInTheDocument()
    expect(screen.getByRole('img', { name: /qr code/i })).toBeInTheDocument()

    fireEvent.change(screen.getByLabelText('Verification code'), { target: { value: '123456' } })
    fireEvent.click(screen.getByRole('button', { name: 'Confirm' }))
    expect(await screen.findByText('AAAAA-11111')).toBeInTheDocument()
    expect(screen.queryByText('SECRETSECRET')).toBeNull()
    expect(screen.getByRole('button', { name: /download/i })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /copy/i })).toBeInTheDocument()
  })

  it('shows an inline error for a wrong confirmation code', async () => {
    route({
      'POST /auth/2fa/setup': () => ({ secret: 'SECRETSECRET', otpauthUri: 'otpauth://totp/x?secret=SECRETSECRET' }),
      'POST /auth/2fa/confirm': () => {
        throw new ApiError(400, 'bad', 'INVALID_CODE')
      },
    })
    renderPage(false)
    fireEvent.click(screen.getByRole('button', { name: /enable two-factor/i }))
    await screen.findByText('SECRETSECRET')
    fireEvent.change(screen.getByLabelText('Verification code'), { target: { value: '111111' } })
    fireEvent.click(screen.getByRole('button', { name: 'Confirm' }))
    expect(await screen.findByText(/code is not valid/i)).toBeInTheDocument()
  })

  it('disables with password and code', async () => {
    route({ 'POST /auth/2fa/disable': () => undefined })
    renderPage(true)
    expect(screen.getByText('Enabled')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /disable two-factor/i }))
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'pw' } })
    fireEvent.change(screen.getByLabelText('Authentication code'), { target: { value: '123456' } })
    fireEvent.click(screen.getByRole('button', { name: 'Disable' }))
    await waitFor(() =>
      expect(apiFetchMock).toHaveBeenCalledWith('/auth/2fa/disable', { method: 'POST', body: { password: 'pw', code: '123456' } }),
    )
  })

  it('explains that an admin requirement prevents disabling', async () => {
    route({
      'POST /auth/2fa/disable': () => {
        throw new ApiError(403, 'required', 'TWO_FACTOR_REQUIRED')
      },
    })
    renderPage(true)
    fireEvent.click(screen.getByRole('button', { name: /disable two-factor/i }))
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'pw' } })
    fireEvent.change(screen.getByLabelText('Authentication code'), { target: { value: '123456' } })
    fireEvent.click(screen.getByRole('button', { name: 'Disable' }))
    expect(await screen.findByText(/required for administrators/i)).toBeInTheDocument()
  })
})
