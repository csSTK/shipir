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

function renderLoginWithLockout(retryAfterSeconds?: number) {
  apiFetchMock.mockImplementation((path: string) =>
    path === '/auth/oidc/config'
      ? Promise.resolve({ enabled: false, displayName: null, localLoginEnabled: true })
      : Promise.reject(new ApiError(429, 'x', 'TOO_MANY_ATTEMPTS', undefined, undefined, retryAfterSeconds)),
  )
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <LoginPage />
    </QueryClientProvider>,
  )
}

async function submitLogin() {
  fireEvent.change(await screen.findByLabelText('Username'), { target: { value: 'admin' } })
  fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'wrong' } })
  fireEvent.click(screen.getByRole('button', { name: /^sign in$/i }))
}

describe('LoginPage throttling', () => {
  it('shows the wait from Retry-After on 429', async () => {
    renderLoginWithLockout(42)
    await submitLogin()
    expect(await screen.findByRole('alert')).toHaveTextContent('Too many attempts, try again in 42 seconds.')
  })

  it('shows a generic wait message when Retry-After is missing', async () => {
    renderLoginWithLockout()
    await submitLogin()
    expect(await screen.findByRole('alert')).toHaveTextContent('Too many attempts. Try again later.')
  })
})
