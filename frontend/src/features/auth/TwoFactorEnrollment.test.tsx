import { StrictMode } from 'react'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { QueryClientProvider } from '@tanstack/react-query'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { queryClient } from '../../app/queryClient.ts'
import { apiFetch } from '../../shared/api/client.ts'
import { TwoFactorEnrollment } from './TwoFactorEnrollment.tsx'

vi.mock('../../shared/api/client.ts', async (importActual) => ({
  ...(await importActual<typeof import('../../shared/api/client.ts')>()),
  apiFetch: vi.fn(),
}))
const apiFetchMock = vi.mocked(apiFetch)

const setup = { secret: 'SECRETSECRET', otpauthUri: 'otpauth://totp/Shipir:pat?secret=SECRETSECRET' }

function setupCalls() {
  return apiFetchMock.mock.calls.filter(([path]) => path === '/auth/2fa/setup')
}

function renderEnrollment({ autoStart, strict = false }: { autoStart: boolean; strict?: boolean }) {
  const tree = (
    <QueryClientProvider client={queryClient}>
      <TwoFactorEnrollment autoStart={autoStart} onFinished={vi.fn()} />
    </QueryClientProvider>
  )
  render(strict ? <StrictMode>{tree}</StrictMode> : tree)
}

beforeEach(() => {
  queryClient.clear()
  apiFetchMock.mockImplementation((path: string) => {
    if (path === '/auth/2fa/setup') return Promise.resolve(setup)
    if (path === '/auth/2fa/confirm') return Promise.resolve({ recoveryCodes: ['AAAAA-11111'] })
    return Promise.resolve(undefined)
  })
})
afterEach(() => {
  cleanup()
  vi.resetAllMocks()
})

describe('TwoFactorEnrollment', () => {
  it('starts the setup once when autoStart runs under StrictMode', async () => {
    renderEnrollment({ autoStart: true, strict: true })
    expect(await screen.findByText('SECRETSECRET')).toBeInTheDocument()
    expect(setupCalls()).toHaveLength(1)
  })

  it('does not retain the secret or the recovery codes in the mutation cache once confirmed', async () => {
    renderEnrollment({ autoStart: false })
    fireEvent.click(screen.getByRole('button', { name: /enable two-factor/i }))
    await screen.findByText('SECRETSECRET')
    fireEvent.change(screen.getByLabelText('Verification code'), { target: { value: '123456' } })
    fireEvent.click(screen.getByRole('button', { name: 'Confirm' }))
    expect(await screen.findByText('AAAAA-11111')).toBeInTheDocument()

    // Settled mutations stay cached until the cache garbage-collects them; wait for that before checking.
    await waitFor(() => {
      const retained = JSON.stringify(queryClient.getMutationCache().getAll().map((mutation) => mutation.state.data))
      expect(retained).not.toContain('SECRETSECRET')
      expect(retained).not.toContain('AAAAA-11111')
    })
  })
})
