import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, apiFetch } from '../shared/api/client.ts'
import { App } from './App.tsx'
import { queryClient } from './queryClient.ts'

vi.mock('../shared/api/client.ts', async (importActual) => ({
  ...(await importActual<typeof import('../shared/api/client.ts')>()),
  apiFetch: vi.fn(),
}))

const apiFetchMock = vi.mocked(apiFetch)

function route(handlers: Record<string, () => unknown>) {
  apiFetchMock.mockImplementation(async (path, options) => {
    const handler = handlers[`${options?.method ?? 'GET'} ${path}`]
    if (!handler) throw new Error(`unexpected call ${options?.method ?? 'GET'} ${path}`)
    return handler() as never
  })
}

const unauthorized = () => {
  throw new ApiError(401, 'unauthorized')
}

beforeEach(() => queryClient.clear())
afterEach(() => {
  cleanup()
  vi.resetAllMocks()
})

describe('App auth gate', () => {
  it('renders the login page when /me answers 401', async () => {
    route({ 'GET /auth/me': unauthorized })
    render(<App />)

    expect(await screen.findByLabelText('Username')).toBeInTheDocument()
    expect(screen.getByLabelText('Password')).toBeInTheDocument()
  })

  it('shows the app after a successful login', async () => {
    route({
      'GET /auth/me': unauthorized,
      'POST /auth/login': () => ({ username: 'admin', role: 'ADMIN', setupRequired: false }),
    })
    render(<App />)

    fireEvent.change(await screen.findByLabelText('Username'), { target: { value: 'admin' } })
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'secret' } })
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }))

    expect(await screen.findByRole('heading', { name: 'Shipir' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /admin/ })).toBeInTheDocument()
    expect(apiFetchMock).toHaveBeenCalledWith('/auth/login', {
      method: 'POST',
      body: { username: 'admin', password: 'secret' },
    })
  })

  it('shows a generic error and keeps the username on a 401 login', async () => {
    route({
      'GET /auth/me': unauthorized,
      'POST /auth/login': unauthorized,
    })
    render(<App />)

    fireEvent.change(await screen.findByLabelText('Username'), { target: { value: 'admin' } })
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'wrong' } })
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Invalid username or password')
    expect(screen.getByLabelText('Username')).toHaveValue('admin')
    expect(screen.getByRole('button', { name: 'Sign in' })).toBeEnabled()
  })

  it('disables the form while the login is pending', async () => {
    route({ 'GET /auth/me': unauthorized, 'POST /auth/login': () => new Promise(() => {}) })
    render(<App />)

    fireEvent.change(await screen.findByLabelText('Username'), { target: { value: 'admin' } })
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'x' } })
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }))

    await waitFor(() => expect(screen.getByRole('button', { name: 'Signing in…' })).toBeDisabled())
    expect(screen.getByLabelText('Username')).toBeDisabled()
  })

  it('renders the setup page, not the app, while setupRequired', async () => {
    route({ 'GET /auth/me': () => ({ username: 'admin', role: 'ADMIN', setupRequired: true }) })
    render(<App />)

    expect(await screen.findByLabelText('New password')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Sign out' })).not.toBeInTheDocument()
  })

  it('returns to the login page after sign out', async () => {
    let signedIn = true
    route({
      'GET /auth/me': () => {
        if (!signedIn) return unauthorized()
        return { username: 'admin', role: 'ADMIN', setupRequired: false }
      },
      'POST /auth/logout': () => {
        signedIn = false
      },
    })
    render(<App />)

    fireEvent.click(await screen.findByRole('button', { name: /admin/ }))
    fireEvent.click(screen.getByRole('menuitem', { name: 'Sign out' }))
    expect(await screen.findByLabelText('Username')).toBeInTheDocument()
  })

  it('shows a not-allowed message for non-admins on /settings/*', async () => {
    window.history.pushState({}, '', '/settings/retention')
    route({ 'GET /auth/me': () => ({ username: 'v', role: 'VIEWER', setupRequired: false }) })
    render(<App />)

    expect(await screen.findByText(/not allowed to view/i)).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Retention' })).not.toBeInTheDocument()
    window.history.pushState({}, '', '/')
  })

  it('shows the settings placeholder to admins', async () => {
    window.history.pushState({}, '', '/settings/retention')
    route({ 'GET /auth/me': () => ({ username: 'a', role: 'ADMIN', setupRequired: false }) })
    render(<App />)

    expect(await screen.findByRole('heading', { name: 'Retention' })).toBeInTheDocument()
    window.history.pushState({}, '', '/')
  })
})

describe('legacy stack URLs', () => {
  afterEach(() => window.history.pushState({}, '', '/'))

  it.each([
    ['/environments/h1/stacks', '/environments/h1/deployments'],
    ['/environments/h1/stacks/d9', '/environments/h1/deployments/d9'],
  ])('redirects %s to %s', async (from, to) => {
    window.history.pushState({}, '', from)
    route({
      'GET /auth/me': () => ({ username: 'admin', role: 'ADMIN', setupRequired: false }),
      'GET /hosts/overview': () => [],
      'GET /hosts': () => [],
      'GET /notifications/unread-count': () => ({ count: 0 }),
      'GET /hosts/h1/deployments': () => [],
      'GET /hosts/h1/managed-deployments': () => [],
      'GET /hosts/h1/managed-deployments/d9': () => new Promise(() => {}),
      'GET /hosts/h1/managed-deployments/d9/versions': () => new Promise(() => {}),
    })
    render(<App />)

    await waitFor(() => expect(window.location.pathname).toBe(to))
  })
})
