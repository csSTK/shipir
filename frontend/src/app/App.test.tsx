import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, apiFetch } from '../shared/api/client.ts'
import { applyLanguage } from '../shared/i18n/index.ts'
import { App } from './App.tsx'
import { queryClient } from './queryClient.ts'

vi.mock('../features/containers/exec/ContainerConsolePage.tsx', () => ({ ContainerConsolePage: () => <h1>Console page</h1> }))
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
  applyLanguage('en')
  vi.resetAllMocks()
})

describe('App language', () => {
  const user = { username: 'admin', role: 'ADMIN', setupRequired: false }
  const shell = { 'GET /hosts/overview': () => [] }

  it('shows the app in the language the user chose', async () => {
    route({ ...shell, 'GET /auth/me': () => ({ ...user, locale: 'de' }) })
    render(<App />)

    expect(await screen.findByRole('link', { name: 'Umgebungen' })).toBeInTheDocument()
    expect(document.documentElement.lang).toBe('de')
  })

  it('falls back to the browser language while the user has chosen none', async () => {
    route({ ...shell, 'GET /auth/me': () => ({ ...user, locale: null }) })
    render(<App />)

    expect(await screen.findByRole('link', { name: 'Environments' })).toBeInTheDocument()
  })
})

describe('App language without a session', () => {
  afterEach(() => vi.unstubAllGlobals())
  const germanBrowser = () => vi.spyOn(navigator, 'languages', 'get').mockReturnValue(['de-DE'])

  it('shows the login page in the browser language', async () => {
    germanBrowser()
    route({ 'GET /auth/me': unauthorized })
    render(<App />)

    expect(await screen.findByLabelText('Benutzername')).toBeInTheDocument()
  })

  it('falls back to the browser language after the user logs out', async () => {
    germanBrowser()
    let loggedIn = true
    route({
      'GET /auth/me': () => (loggedIn ? { username: 'admin', role: 'ADMIN', setupRequired: false, locale: 'en' } : unauthorized()),
      'GET /hosts/overview': () => [],
      'POST /auth/logout': () => {
        loggedIn = false
      },
    })
    render(<App />)
    expect(await screen.findByRole('link', { name: 'Environments' })).toBeInTheDocument()

    const trigger = screen.getByRole('button', { name: /admin/ })
    trigger.focus()
    fireEvent.keyDown(trigger, { key: 'Enter' })
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Sign out' }))

    expect(await screen.findByLabelText('Benutzername')).toBeInTheDocument()
  })
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

    expect(await screen.findByRole('link', { name: 'Shipir' })).toBeInTheDocument()
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

    const trigger = await screen.findByRole('button', { name: /admin/ })
    trigger.focus()
    fireEvent.keyDown(trigger, { key: 'Enter' })
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Sign out' }))
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

describe('console route gate', () => {
  afterEach(() => window.history.pushState({}, '', '/'))

  const capabilityCalls = () => apiFetchMock.mock.calls.map((c) => c[0]).filter((p) => p === '/exec/capabilities')

  function routeAs(role: string, capabilities: () => unknown) {
    window.history.pushState({}, '', '/environments/h1/containers/c1/console')
    route({
      'GET /auth/me': () => ({ username: 'u', role, setupRequired: false }),
      'GET /hosts/overview': () => [],
      'GET /exec/capabilities': capabilities,
    })
  }

  it.each([
    ['VIEWER', false],
    ['VIEWER', true],
    ['OPERATOR', false],
  ])('shows not-allowed to a %s when operator exec is %s', async (role, operatorEnabled) => {
    routeAs(role, () => ({ operatorEnabled }))
    render(<App />)

    expect(await screen.findByText(/not allowed to view/i)).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Console page' })).not.toBeInTheDocument()
    if (role === 'VIEWER') expect(capabilityCalls()).toEqual([])
  })

  it('opens the console page for an OPERATOR when operator exec is enabled', async () => {
    routeAs('OPERATOR', () => ({ operatorEnabled: true }))
    render(<App />)

    expect(await screen.findByRole('heading', { name: 'Console page' })).toBeInTheDocument()
  })

  it('does not show not-allowed to an OPERATOR while the capabilities are loading', async () => {
    let resolve: (v: unknown) => void = () => {}
    const pending = new Promise((r) => (resolve = r))
    routeAs('OPERATOR', () => pending)
    render(<App />)

    await waitFor(() => expect(capabilityCalls()).toHaveLength(1))
    expect(screen.queryByText(/not allowed to view/i)).not.toBeInTheDocument()
    resolve({ operatorEnabled: true })
    expect(await screen.findByRole('heading', { name: 'Console page' })).toBeInTheDocument()
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
