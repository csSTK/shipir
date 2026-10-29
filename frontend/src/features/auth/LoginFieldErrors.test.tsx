import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, apiFetch } from '../../shared/api/client.ts'
import { LoginPage } from './LoginPage.tsx'

vi.mock('../../shared/api/client.ts', async (importActual) => ({
  ...(await importActual<typeof import('../../shared/api/client.ts')>()),
  apiFetch: vi.fn(),
}))
const apiFetchMock = vi.mocked(apiFetch)

afterEach(() => {
  cleanup()
  vi.resetAllMocks()
})

function renderLogin(failure: ApiError) {
  apiFetchMock.mockImplementation((path: string) =>
    path === '/auth/oidc/config'
      ? Promise.resolve({ enabled: false, displayName: null, localLoginEnabled: true })
      : Promise.reject(failure),
  )
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <LoginPage />
    </QueryClientProvider>,
  )
}

async function submit() {
  fireEvent.change(await screen.findByLabelText('Username'), { target: { value: 'admin' } })
  fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'wrong' } })
  fireEvent.click(screen.getByRole('button', { name: /^sign in$/i }))
}

describe('LoginPage field errors', () => {
  it('links a rejected credential pair to both inputs', async () => {
    renderLogin(new ApiError(401, 'no', 'UNAUTHENTICATED'))
    await submit()
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(/invalid username or password/i)
    for (const label of ['Username', 'Password']) {
      const input = screen.getByLabelText(label)
      expect(input).toHaveAttribute('aria-describedby', alert.id)
      expect(input).toHaveAttribute('aria-invalid', 'true')
    }
  })

  it('keeps form-level failures (throttling) unlinked to the inputs', async () => {
    renderLogin(new ApiError(429, 'x', 'TOO_MANY_ATTEMPTS', undefined, undefined, 42))
    await submit()
    await screen.findByRole('alert')
    expect(screen.getByLabelText('Username')).not.toHaveAttribute('aria-invalid')
    expect(screen.getByLabelText('Password')).not.toHaveAttribute('aria-describedby')
  })

  it('links an invalid 2FA code to the code input', async () => {
    apiFetchMock.mockImplementation((path: string) =>
      path === '/auth/oidc/config'
        ? Promise.resolve({ enabled: false, displayName: null, localLoginEnabled: true })
        : path === '/auth/login'
          ? Promise.resolve({ mfaRequired: true })
          : Promise.reject(new ApiError(400, 'bad', 'INVALID_CODE')),
    )
    render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <LoginPage />
      </QueryClientProvider>,
    )
    await submit()
    const code = await screen.findByLabelText('Authentication code')
    fireEvent.change(code, { target: { value: '000000' } })
    fireEvent.click(screen.getByRole('button', { name: 'Verify' }))
    const alert = await screen.findByText(/code is not valid/i)
    expect(code).toHaveAttribute('aria-describedby', alert.id)
    expect(code).toHaveAttribute('aria-invalid', 'true')
  })
})
