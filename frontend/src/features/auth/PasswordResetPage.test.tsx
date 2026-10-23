import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { QueryClientProvider } from '@tanstack/react-query'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { queryClient } from '../../app/queryClient.ts'
import { ApiError, apiFetch } from '../../shared/api/client.ts'
import { LoginPage } from './LoginPage.tsx'

vi.mock('../../shared/api/client.ts', async (importActual) => ({
  ...(await importActual<typeof import('../../shared/api/client.ts')>()),
  apiFetch: vi.fn(),
}))
const apiFetchMock = vi.mocked(apiFetch)

const GOOD = 'Abcdefghijklm1!'

function renderLogin() {
  return render(
    <QueryClientProvider client={queryClient}>
      <LoginPage />
    </QueryClientProvider>,
  )
}
const type = (label: string | RegExp, value: string) =>
  fireEvent.change(screen.getByLabelText(label), { target: { value } })

async function openStepTwo() {
  renderLogin()
  fireEvent.click(screen.getByRole('button', { name: /forgot password/i }))
  type('Username', 'admin')
  apiFetchMock.mockResolvedValueOnce(undefined)
  fireEvent.click(screen.getByRole('button', { name: /request code/i }))
  await screen.findByLabelText(/code/i)
}
const confirm = () => screen.getByRole('button', { name: /reset password/i })
function fillStepTwo(code = '123456') {
  type(/6-digit code/i, code)
  type('New password', GOOD)
  type('Confirm new password', GOOD)
}

beforeEach(() => queryClient.clear())
afterEach(() => {
  cleanup()
  vi.resetAllMocks()
})

describe('password reset', () => {
  it('opens the username step from the login page and goes back', () => {
    renderLogin()
    fireEvent.click(screen.getByRole('button', { name: /forgot password/i }))
    expect(screen.getByRole('heading', { name: /reset your password/i })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /back to sign in/i }))
    expect(screen.getByRole('heading', { name: /sign in to shipir/i })).toBeInTheDocument()
  })

  it('shows a neutral message and moves to the code step after requesting', async () => {
    await openStepTwo()
    expect(apiFetchMock).toHaveBeenCalledWith('/auth/password-reset/request', {
      method: 'POST',
      body: { username: 'admin' },
    })
    expect(screen.getByText('If the account exists, a code has been issued.')).toBeInTheDocument()
    expect(screen.getByLabelText(/6-digit code/i)).toHaveAttribute('autocomplete', 'one-time-code')
    expect(screen.getByLabelText(/6-digit code/i)).toHaveAttribute('inputmode', 'numeric')
  })

  it('disables the request button while pending', async () => {
    renderLogin()
    fireEvent.click(screen.getByRole('button', { name: /forgot password/i }))
    type('Username', 'admin')
    apiFetchMock.mockReturnValue(new Promise(() => {}))
    fireEvent.click(screen.getByRole('button', { name: /request code/i }))
    await waitFor(() => expect(screen.getByRole('button', { name: /requesting/i })).toBeDisabled())
  })

  it('keeps reset disabled until code and password rules pass', async () => {
    await openStepTwo()
    expect(confirm()).toBeDisabled()
    fillStepTwo('12')
    expect(confirm()).toBeDisabled()
    type(/6-digit code/i, '123456')
    expect(confirm()).toBeEnabled()
    type('Confirm new password', GOOD + 'x')
    expect(confirm()).toBeDisabled()
  })

  it('disables reset while the confirmation is pending', async () => {
    await openStepTwo()
    fillStepTwo()
    apiFetchMock.mockReturnValue(new Promise(() => {}))
    fireEvent.click(confirm())
    await waitFor(() => expect(screen.getByRole('button', { name: /resetting/i })).toBeDisabled())
  })

  it('shows an inline error with a new-code action for INVALID_RESET_CODE', async () => {
    await openStepTwo()
    fillStepTwo()
    apiFetchMock.mockRejectedValueOnce(new ApiError(400, 'bad', 'INVALID_RESET_CODE'))
    fireEvent.click(confirm())
    expect(await screen.findByRole('alert')).toHaveTextContent(/invalid or expired/i)
    expect(apiFetchMock).toHaveBeenLastCalledWith('/auth/password-reset/confirm', {
      method: 'POST',
      body: { username: 'admin', code: '123456', newPassword: GOOD },
    })

    fireEvent.click(screen.getByRole('button', { name: /request a new code/i }))
    expect(screen.getByRole('button', { name: /request code/i })).toBeInTheDocument()
  })

  it('lists violations for PASSWORD_POLICY', async () => {
    await openStepTwo()
    fillStepTwo()
    apiFetchMock.mockRejectedValueOnce(
      new ApiError(400, 'x', 'PASSWORD_POLICY', ['MISSING_SYMBOL']),
    )
    fireEvent.click(confirm())
    expect(await screen.findByText('The password needs a symbol.')).toBeInTheDocument()
  })

  it('returns to login with a success notice after a reset', async () => {
    await openStepTwo()
    fillStepTwo()
    apiFetchMock.mockResolvedValueOnce(undefined)
    fireEvent.click(confirm())
    expect(await screen.findByRole('heading', { name: /sign in to shipir/i })).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent(/password was reset/i)
  })
})
