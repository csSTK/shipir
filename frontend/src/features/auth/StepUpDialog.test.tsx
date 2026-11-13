import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../shared/api/client.ts'
import { StepUpDialog } from './StepUpDialog.tsx'
import type { StepUpStatus } from './stepUp.ts'

const apiFetch = vi.fn()
vi.mock('../../shared/api/client.ts', async (importActual) => ({
  ...(await importActual<typeof import('../../shared/api/client.ts')>()),
  apiFetch: (...args: unknown[]) => apiFetch(...args),
}))

const assign = vi.fn()

beforeEach(() => {
  vi.stubGlobal('location', { ...window.location, pathname: '/settings/backup', assign })
})
afterEach(() => {
  cleanup()
  apiFetch.mockReset()
  assign.mockReset()
  vi.unstubAllGlobals()
})

function renderDialog(status: StepUpStatus, onConfirm = vi.fn().mockResolvedValue(undefined), onFailure = vi.fn()) {
  apiFetch.mockResolvedValue(status)
  render(
    <QueryClientProvider client={new QueryClient()}>
      <StepUpDialog onCancel={vi.fn()} onConfirm={onConfirm} onFailure={onFailure} />
    </QueryClientProvider>,
  )
  return { onConfirm, onFailure }
}

const password: StepUpStatus = { method: 'PASSWORD', totpRequired: false, fresh: false, windowSeconds: 300 }

describe('StepUpDialog', () => {
  it('sends the password for a local user', async () => {
    const { onConfirm } = renderDialog(password)
    fireEvent.change(await screen.findByLabelText('Current password'), { target: { value: 'hunter2-hunter2' } })
    fireEvent.click(screen.getByRole('button', { name: 'Confirm' }))
    await waitFor(() => expect(onConfirm).toHaveBeenCalledWith({ password: 'hunter2-hunter2' }))
    expect(screen.queryByLabelText('Authentication code')).not.toBeInTheDocument()
  })

  it('also asks for a code, and sends it, when two-factor is on', async () => {
    const { onConfirm } = renderDialog({ ...password, totpRequired: true })
    const dialog = await screen.findByRole('dialog')
    const confirm = await within(dialog).findByRole('button', { name: 'Confirm' })
    fireEvent.change(within(dialog).getByLabelText('Current password'), { target: { value: 'pw-pw-pw' } })
    expect(confirm).toBeDisabled()
    fireEvent.change(within(dialog).getByLabelText('Authentication code'), { target: { value: ' 123456 ' } })
    fireEvent.click(confirm)
    await waitFor(() => expect(onConfirm).toHaveBeenCalledWith({ password: 'pw-pw-pw', code: '123456' }))
  })

  it('keeps the dialog for a step-up refusal, clears the password and says why', async () => {
    const { onFailure } = renderDialog(password, vi.fn().mockRejectedValue(new ApiError(403, 'x', 'STEP_UP_INVALID')))
    fireEvent.change(await screen.findByLabelText('Current password'), { target: { value: 'wrong-wrong' } })
    fireEvent.click(screen.getByRole('button', { name: 'Confirm' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('password or code is not correct')
    expect(screen.getByLabelText('Current password')).toHaveValue('')
    expect(onFailure).not.toHaveBeenCalled()
  })

  it('names the wait after a lockout (429)', async () => {
    const err = new ApiError(429, 'x', 'TOO_MANY_ATTEMPTS', undefined, undefined, 90)
    renderDialog(password, vi.fn().mockRejectedValue(err))
    fireEvent.change(await screen.findByLabelText('Current password'), { target: { value: 'pw-pw-pw' } })
    fireEvent.click(screen.getByRole('button', { name: 'Confirm' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('90 seconds')
  })

  it('hands any other error to the caller', async () => {
    const err = new ApiError(400, 'x', 'INVALID_EXPIRY')
    const { onFailure } = renderDialog(password, vi.fn().mockRejectedValue(err))
    fireEvent.change(await screen.findByLabelText('Current password'), { target: { value: 'pw-pw-pw' } })
    fireEvent.click(screen.getByRole('button', { name: 'Confirm' }))
    await waitFor(() => expect(onFailure).toHaveBeenCalledWith(err))
  })

  it('sends an SSO user to the provider when the last sign-in is too old', async () => {
    const { onConfirm } = renderDialog({ method: 'SSO', totpRequired: false, fresh: false, windowSeconds: 300 })
    fireEvent.click(await screen.findByRole('button', { name: 'Sign in with SSO' }))
    expect(assign).toHaveBeenCalledWith('/api/auth/oidc/reauthorize?return=%2Fsettings%2Fbackup')
    expect(onConfirm).not.toHaveBeenCalled()
    expect(screen.queryByLabelText('Current password')).not.toBeInTheDocument()
  })

  it('lets an SSO user with a fresh sign-in continue, and falls back to the provider if the server disagrees', async () => {
    const { onConfirm } = renderDialog(
      { method: 'SSO', totpRequired: false, fresh: true, windowSeconds: 300 },
      vi.fn().mockRejectedValue(new ApiError(403, 'x', 'STEP_UP_SSO_REQUIRED')),
    )
    fireEvent.click(await screen.findByRole('button', { name: 'Continue' }))
    await waitFor(() => expect(onConfirm).toHaveBeenCalledWith({}))
    expect(await screen.findByRole('button', { name: 'Sign in with SSO' })).toBeInTheDocument()
  })
})
