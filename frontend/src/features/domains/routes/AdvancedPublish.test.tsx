import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, cleanup, fireEvent, render, renderHook, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useAdvancedMode } from '../advancedMode.ts'
import { PublishDialog } from './PublishDialog.tsx'

const apiFetch = vi.fn()
vi.mock('../../../shared/api/client.ts', async (importActual) => ({
  ...(await importActual<typeof import('../../../shared/api/client.ts')>()),
  apiFetch: (...args: unknown[]) => apiFetch(...args),
}))

const route = {
  id: 'r1',
  hostname: 'app.example.com',
  aliases: ['www.example.com'],
  containerName: 'web',
  targetPort: 8080,
  backendScheme: 'HTTPS',
  pathPrefix: '/app',
  entrypoint: 'websecure',
  certMode: 'ACME_HTTP',
  certificateId: null,
  redirectHttps: true,
  basicAuthUsers: ['admin'],
  responseHeaders: { 'X-Frame-Options': 'DENY' },
  advancedActive: true,
  containerState: 'running',
}
const candidates = [{ containerName: 'web', image: 'nginx', state: 'running', ports: [8080], suggestedPort: 8080, published: false }]
const R = '/hosts/h1/proxy/routes'
let proxyType = 'TRAEFIK'
let queryClient: QueryClient

beforeEach(() => {
  localStorage.clear()
  proxyType = 'TRAEFIK'
  apiFetch.mockImplementation((path: string, options?: { method?: string }) => {
    if (path === '/auth/me') return Promise.resolve({ username: 'u', role: 'OPERATOR', setupRequired: false })
    if (options?.method) return Promise.resolve(null)
    if (path === `${R}/candidates`) return Promise.resolve(candidates)
    if (path === '/hosts/h1/proxy/managed') return Promise.resolve({ instance: { type: proxyType, defaultCertMode: 'ACME_HTTP' }, setupBlockedReason: null })
    if (path === '/hosts/h1/proxy/certificates')
      return Promise.resolve([{ id: 'c1', name: 'Wildcard 2027', domains: ['*.example.com'], notAfter: '2027-01-01T00:00:00Z', usedByRoutes: [] }])
    return Promise.resolve(null)
  })
})
afterEach(() => {
  cleanup()
  apiFetch.mockReset()
})

function Wrap({ children }: { children: React.ReactNode }) {
  return (
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>{children}</MemoryRouter>
    </QueryClientProvider>
  )
}
function renderDialog(props: Partial<React.ComponentProps<typeof PublishDialog>> = {}, advanced = true) {
  queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  localStorage.setItem('shipir.domains.advanced.u', advanced ? '1' : '0')
  render(<PublishDialog hostId="h1" containerName="web" onClose={() => {}} {...props} />, { wrapper: Wrap })
  return screen.findByRole('dialog').then(async (dialog) => {
    // the mode is only known once the user is loaded
    if (advanced) await within(dialog).findByLabelText('Path prefix')
    return dialog
  })
}
const type = (el: HTMLElement, value: string) => fireEvent.change(el, { target: { value } })
const writes = () => apiFetch.mock.calls.filter(([, o]) => o?.method)

describe('PublishDialog advanced mode', () => {
  it('shows no advanced fields in simple mode', async () => {
    const dialog = await renderDialog({}, false)
    expect(within(dialog).queryByLabelText('Path prefix')).not.toBeInTheDocument()
    expect(within(dialog).queryByRole('button', { name: 'Add alias' })).not.toBeInTheDocument()
  })

  it('shows the advanced fields in advanced mode', async () => {
    const dialog = await renderDialog()
    for (const label of ['Path prefix', 'Backend protocol', 'Entrypoint', 'HTTPS certificate']) expect(await within(dialog).findByLabelText(label)).toBeInTheDocument()
    for (const name of ['Add alias', 'Add user', 'Add header']) expect(within(dialog).getByRole('button', { name })).toBeInTheDocument()
  })

  it('hides the DNS challenge and the entrypoint for Caddy instead of disabling them', async () => {
    proxyType = 'CADDY'
    const dialog = await renderDialog()
    await within(dialog).findByLabelText('HTTPS certificate')
    await waitFor(() => expect(within(dialog).queryByLabelText('Entrypoint')).not.toBeInTheDocument())
    expect(within(dialog).queryByRole('option', { name: /DNS challenge/ })).not.toBeInTheDocument()
  })

  it('sends only the touched advanced fields when publishing', async () => {
    const dialog = await renderDialog()
    type(within(dialog).getByLabelText('Domain'), 'blog.example.com')
    type(within(dialog).getByLabelText('Path prefix'), '/blog')
    fireEvent.click(within(dialog).getByRole('button', { name: 'Publish' }))
    await waitFor(() => expect(writes()).toHaveLength(1))
    expect(writes()[0]).toEqual([R, { method: 'POST', body: { containerName: 'web', hostname: 'blog.example.com', pathPrefix: '/blog' } }])
  })

  it('keeps the entered values when the mode is switched off and on again, and still sends them', async () => {
    const dialog = await renderDialog()
    type(within(dialog).getByLabelText('Domain'), 'blog.example.com')
    type(within(dialog).getByLabelText('Path prefix'), '/blog')
    const mode = renderHook(() => useAdvancedMode(), { wrapper: Wrap })
    act(() => mode.result.current[1](false))
    await waitFor(() => expect(within(dialog).queryByLabelText('Path prefix')).not.toBeInTheDocument())
    expect(within(dialog).getByLabelText('Domain')).toHaveValue('blog.example.com')
    act(() => mode.result.current[1](true))
    expect(await within(dialog).findByLabelText('Path prefix')).toHaveValue('/blog')
  })

  it('sends a new basic auth user and an own certificate; passwords do not linger in the mutation cache', async () => {
    const dialog = await renderDialog()
    type(within(dialog).getByLabelText('Domain'), '*.example.com')
    type(within(dialog).getByLabelText('HTTPS certificate'), 'CUSTOM')
    type(await within(dialog).findByLabelText('Your certificate'), 'c1')
    fireEvent.click(within(dialog).getByRole('button', { name: 'Add user' }))
    type(within(dialog).getByLabelText('User name'), 'bob')
    type(within(dialog).getByLabelText('Password'), 's3cret')
    fireEvent.click(within(dialog).getByRole('button', { name: 'Add header' }))
    type(within(dialog).getByLabelText('Header name'), 'X-Frame-Options')
    type(within(dialog).getByLabelText('Header value'), 'DENY')
    fireEvent.click(within(dialog).getByRole('button', { name: 'Publish' }))
    await waitFor(() => expect(writes()).toHaveLength(1))
    expect(writes()[0][1].body).toEqual({
      containerName: 'web',
      hostname: '*.example.com',
      certMode: 'CUSTOM',
      certificateId: 'c1',
      basicAuth: { bob: 's3cret' },
      responseHeaders: { 'X-Frame-Options': 'DENY' },
    })
    await waitFor(() => expect(queryClient.getMutationCache().getAll()).toHaveLength(0))
  })

  it('blocks a user without a password and a bad path prefix', async () => {
    const dialog = await renderDialog()
    type(within(dialog).getByLabelText('Domain'), 'blog.example.com')
    type(within(dialog).getByLabelText('Path prefix'), 'blog')
    fireEvent.click(within(dialog).getByRole('button', { name: 'Add user' }))
    type(within(dialog).getByLabelText('User name'), 'bob')
    fireEvent.click(within(dialog).getByRole('button', { name: 'Publish' }))
    expect(await within(dialog).findByText(/must start with/i)).toBeInTheDocument()
    expect(within(dialog).getByText(/needs a password/i)).toBeInTheDocument()
    expect(writes()).toHaveLength(0)
  })

  it('edits a route: stored values are shown, passwords are never prefilled, untouched users are not sent', async () => {
    const dialog = await renderDialog({ route: route as never, containerName: undefined })
    expect(within(dialog).getByLabelText('Path prefix')).toHaveValue('/app')
    expect(await within(dialog).findByLabelText('Entrypoint')).toHaveValue('websecure')
    expect(within(dialog).getByDisplayValue('www.example.com')).toBeInTheDocument()
    expect(within(dialog).getByDisplayValue('admin')).toBeInTheDocument()
    expect(within(dialog).getByLabelText('Password')).toHaveValue('')
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(writes()).toHaveLength(1))
    const body = writes()[0][1].body
    expect(body).toMatchObject({ aliases: ['www.example.com'], backendScheme: 'HTTPS', pathPrefix: '/app', entrypoint: 'websecure', responseHeaders: { 'X-Frame-Options': 'DENY' } })
    expect(body).not.toHaveProperty('basicAuth')
  })

  it('keeps an existing user with an empty password when another user is added', async () => {
    const dialog = await renderDialog({ route: route as never, containerName: undefined })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Add user' }))
    const names = within(dialog).getAllByLabelText('User name')
    type(names[1], 'bob')
    type(within(dialog).getAllByLabelText('Password')[1], 'pw')
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(writes()).toHaveLength(1))
    expect(writes()[0][1].body.basicAuth).toEqual({ admin: '', bob: 'pw' })
  })

  it('does not keep passwords in the mutation cache after a failed save either', async () => {
    const { ApiError } = await import('../../../shared/api/client.ts')
    const dialog = await renderDialog()
    type(within(dialog).getByLabelText('Domain'), 'blog.example.com')
    fireEvent.click(within(dialog).getByRole('button', { name: 'Add user' }))
    type(within(dialog).getByLabelText('User name'), 'bob')
    type(within(dialog).getByLabelText('Password'), 's3cret')
    apiFetch.mockImplementation((_p: string, o?: { method?: string }) => (o?.method ? Promise.reject(new ApiError(502, 'x', 'PROXY_SYNC_FAILED')) : Promise.resolve([])))
    fireEvent.click(within(dialog).getByRole('button', { name: 'Publish' }))
    expect(await within(dialog).findByText(/could not update the proxy/i)).toBeInTheDocument()
    await waitFor(() => expect(JSON.stringify(queryClient.getMutationCache().getAll().map((m) => m.state.variables))).not.toContain('s3cret'))
    // the form keeps the input, so the user can retry
    expect(within(dialog).getByLabelText('Password')).toHaveValue('s3cret')
  })

  it('shows a server problem with the advanced options in plain language', async () => {
    const { ApiError } = await import('../../../shared/api/client.ts')
    const dialog = await renderDialog()
    type(within(dialog).getByLabelText('Domain'), 'blog.example.com')
    apiFetch.mockImplementation((_p: string, o?: { method?: string }) => (o?.method ? Promise.reject(new ApiError(400, 'x', 'CERT_DOES_NOT_COVER_HOST')) : Promise.resolve([])))
    fireEvent.click(within(dialog).getByRole('button', { name: 'Publish' }))
    expect(await within(dialog).findByText(/does not cover/i)).toBeInTheDocument()
  })
})
