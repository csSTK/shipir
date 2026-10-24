import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { QueryClientProvider } from '@tanstack/react-query'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { queryClient } from '../../app/queryClient.ts'
import { ApiError, apiFetch } from '../../shared/api/client.ts'
import { LoginPage } from './LoginPage.tsx'
import { SetupPage } from './SetupPage.tsx'
import { CURRENT_USER_KEY } from './api.ts'

vi.mock('../../shared/api/client.ts', async (importActual) => ({
  ...(await importActual<typeof import('../../shared/api/client.ts')>()),
  apiFetch: vi.fn(),
}))
const apiFetchMock = vi.mocked(apiFetch)

const user = { username: 'admin', role: 'ADMIN', setupRequired: false, setupStep: null, twoFactorEnabled: true }

beforeEach(() => queryClient.clear())
afterEach(() => {
  cleanup()
  vi.resetAllMocks()
})

function renderWith(node: React.ReactNode) {
  return render(<QueryClientProvider client={queryClient}>{node}</QueryClientProvider>)
}

async function reachSecondStep() {
  apiFetchMock.mockResolvedValueOnce({ mfaRequired: true })
  renderWith(<LoginPage />)
  fireEvent.change(screen.getByLabelText('Username'), { target: { value: 'admin' } })
  fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'pw' } })
  fireEvent.click(screen.getByRole('button', { name: 'Sign in' }))
  await screen.findByLabelText('Authentication code')
}

describe('LoginPage second step', () => {
  it('asks for the code instead of signing in when the server requires 2FA', async () => {
    await reachSecondStep()
    expect(queryClient.getQueryData(CURRENT_USER_KEY)).toBeUndefined()
    expect(screen.queryByLabelText('Password')).toBeNull()
  })

  it('verifies the code and signs in', async () => {
    await reachSecondStep()
    apiFetchMock.mockResolvedValueOnce(user)
    fireEvent.change(screen.getByLabelText('Authentication code'), { target: { value: '123456' } })
    fireEvent.click(screen.getByRole('button', { name: 'Verify' }))
    await waitFor(() => expect(queryClient.getQueryData(CURRENT_USER_KEY)).toEqual(user))
    expect(apiFetchMock).toHaveBeenLastCalledWith('/auth/2fa/verify', { method: 'POST', body: { code: '123456' } })
  })

  it('shows an inline error for a wrong code and keeps the step', async () => {
    await reachSecondStep()
    apiFetchMock.mockRejectedValueOnce(new ApiError(401, 'bad', 'INVALID_CODE'))
    fireEvent.change(screen.getByLabelText('Authentication code'), { target: { value: '000000' } })
    fireEvent.click(screen.getByRole('button', { name: 'Verify' }))
    expect(await screen.findByRole('alert')).toHaveTextContent(/code is not valid/i)
    expect(screen.getByLabelText('Authentication code')).toBeInTheDocument()
  })

  it('tells the user when too many attempts were made', async () => {
    await reachSecondStep()
    apiFetchMock.mockRejectedValueOnce(new ApiError(429, 'slow down', 'TOO_MANY_ATTEMPTS'))
    fireEvent.change(screen.getByLabelText('Authentication code'), { target: { value: '000000' } })
    fireEvent.click(screen.getByRole('button', { name: 'Verify' }))
    expect(await screen.findByRole('alert')).toHaveTextContent(/too many attempts/i)
  })

  it('returns to the credentials when the pending sign-in expired', async () => {
    await reachSecondStep()
    apiFetchMock.mockRejectedValueOnce(new ApiError(401, 'expired', 'UNAUTHENTICATED'))
    fireEvent.change(screen.getByLabelText('Authentication code'), { target: { value: '123456' } })
    fireEvent.click(screen.getByRole('button', { name: 'Verify' }))
    expect(await screen.findByLabelText('Password')).toBeInTheDocument()
    expect(screen.getByRole('alert')).toHaveTextContent(/expired/i)
  })
})

describe('SetupPage two-factor step', () => {
  it('shows QR and secret, confirms with a code and then the recovery codes', async () => {
    apiFetchMock.mockResolvedValueOnce({ secret: 'JBSWY3DPEHPK3PXP', otpauthUri: 'otpauth://totp/Shipir:admin?secret=JBSWY3DPEHPK3PXP&issuer=Shipir' })
    renderWith(<SetupPage username="admin" step="TWO_FACTOR" />)
    expect(await screen.findByText('JBSWY3DPEHPK3PXP')).toBeInTheDocument()
    expect(screen.getByRole('img', { name: /qr code/i })).toBeInTheDocument()

    apiFetchMock.mockResolvedValueOnce({ recoveryCodes: ['AAAAA-11111', 'BBBBB-22222'] })
    fireEvent.change(screen.getByLabelText('Verification code'), { target: { value: '654321' } })
    fireEvent.click(screen.getByRole('button', { name: 'Confirm' }))
    expect(await screen.findByText('AAAAA-11111')).toBeInTheDocument()
    expect(screen.queryByText('JBSWY3DPEHPK3PXP')).toBeNull()
    expect(apiFetchMock).toHaveBeenLastCalledWith('/auth/2fa/confirm', { method: 'POST', body: { code: '654321' } })
  })
})
