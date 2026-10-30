import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../../shared/api/client.ts'
import { PublishDialog } from './PublishDialog.tsx'
import { RoutesList } from './RoutesList.tsx'

const apiFetch = vi.fn()
vi.mock('../../../shared/api/client.ts', async (importActual) => ({
  ...(await importActual<typeof import('../../../shared/api/client.ts')>()),
  apiFetch: (...args: unknown[]) => apiFetch(...args),
}))

const route = (over: Record<string, unknown> = {}) => ({
  id: 'r1',
  hostname: 'app.example.com',
  aliases: [],
  containerName: 'web',
  targetPort: 8080,
  backendScheme: 'HTTP',
  pathPrefix: null,
  entrypoint: null,
  certMode: 'ACME_HTTP',
  certificateId: null,
  redirectHttps: true,
  basicAuthUsers: [],
  responseHeaders: {},
  advancedActive: false,
  containerState: 'running',
  ...over,
})
const candidate = (over: Record<string, unknown> = {}) => ({ containerName: 'web', image: 'nginx', state: 'running', ports: [8080], suggestedPort: 8080, published: false, ...over })

let routes: unknown[]
let candidates: unknown[]
let statuses: unknown[]
const R = '/hosts/h1/proxy/routes'

beforeEach(() => {
  routes = [route()]
  candidates = [candidate(), candidate({ containerName: 'api', image: 'api:1', ports: [80, 8080], suggestedPort: null }), candidate({ containerName: 'worker', image: 'w', ports: [], suggestedPort: null })]
  statuses = [{ hostname: 'app.example.com', status: 'ISSUED', notAfter: '2027-01-01T00:00:00Z', lastError: null }]
  apiFetch.mockImplementation((path: string, options?: { method?: string }) => {
    if (options?.method) return Promise.resolve(null)
    if (path === R) return Promise.resolve(routes)
    if (path === `${R}/candidates`) return Promise.resolve(candidates)
    if (path === `${R}/status`) return Promise.resolve(statuses)
    if (path === '/hosts/h1/proxy/managed') return Promise.resolve({ instance: { type: 'TRAEFIK', defaultCertMode: 'ACME_HTTP' }, setupBlockedReason: null })
    return Promise.resolve(null)
  })
})
afterEach(() => {
  cleanup()
  apiFetch.mockReset()
})

function wrap(ui: React.ReactNode) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(<QueryClientProvider client={queryClient}>
      <MemoryRouter>{ui}</MemoryRouter>
    </QueryClientProvider>)
}
const renderList = (canPublish = true) => wrap(<RoutesList hostId="h1" canPublish={canPublish} />)

function writeCalls() {
  return apiFetch.mock.calls.filter(([, o]) => o?.method)
}
function openMenu(trigger: HTMLElement) {
  trigger.focus()
  fireEvent.keyDown(trigger, { key: 'Enter' })
}
async function openPublish() {
  fireEvent.click(await screen.findByRole('button', { name: 'Publish a container' }))
  return screen.findByRole('dialog')
}
const type = (el: HTMLElement, value: string) => fireEvent.change(el, { target: { value } })

describe('RoutesList', () => {
  it('lists the domain as a link, the container, the port and a status badge', async () => {
    renderList()
    const table = await screen.findByRole('table', { name: 'published domains' })
    const link = within(table).getByRole('link', { name: 'app.example.com' })
    expect(link).toHaveAttribute('href', 'https://app.example.com')
    expect(link).toHaveAttribute('rel', expect.stringContaining('noopener'))
    expect(within(table).getByText('web')).toBeInTheDocument()
    expect(within(table).getByText('8080')).toBeInTheDocument()
    expect(await within(table).findByText('Certificate issued')).toBeInTheDocument()
  })

  it('shows issued, pending and failed certificates, the failure reason as plain text', async () => {
    routes = [route(), route({ id: 'r2', hostname: 'b.example.com' }), route({ id: 'r3', hostname: 'c.example.com' })]
    statuses = [
      { hostname: 'app.example.com', status: 'ISSUED', notAfter: null, lastError: null },
      { hostname: 'b.example.com', status: 'PENDING', notAfter: null, lastError: null },
      { hostname: 'c.example.com', status: 'FAILED', notAfter: null, lastError: '<img src=x onerror=alert(1)> rate limited' },
    ]
    renderList()
    expect(await screen.findByText('Certificate issued')).toBeInTheDocument()
    expect(screen.getByText('Waiting for certificate')).toBeInTheDocument()
    expect(screen.getByText('Certificate failed')).toBeInTheDocument()
    expect(screen.getByText('<img src=x onerror=alert(1)> rate limited')).toBeInTheDocument()
    expect(document.querySelector('img')).toBeNull()
  })

  it('flags routes with advanced settings and containers that are gone', async () => {
    routes = [route({ advancedActive: true, containerState: null })]
    renderList()
    expect(await screen.findByText('Advanced settings')).toBeInTheDocument()
    expect(screen.getByText('Container missing')).toBeInTheDocument()
  })

  it('shows the list without any action for a viewer', async () => {
    renderList(false)
    await screen.findByRole('table', { name: 'published domains' })
    expect(screen.queryByRole('button', { name: 'Publish a container' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^Edit/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /More actions/i })).not.toBeInTheDocument()
  })

  it('explains the empty state and offers publishing to operators only', async () => {
    routes = []
    renderList()
    expect(await screen.findByText('No domains published yet')).toBeInTheDocument()
    expect(screen.getAllByRole('button', { name: 'Publish a container' }).length).toBeGreaterThan(0)
    cleanup()
    renderList(false)
    expect(await screen.findByText('No domains published yet')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Publish a container' })).not.toBeInTheDocument()
  })

  it('unpublishes after a confirmation', async () => {
    renderList()
    await screen.findByRole('table', { name: 'published domains' })
    openMenu(await screen.findByRole('button', { name: /More actions/i }))
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Unpublish' }))
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText(/app\.example\.com/)).toBeInTheDocument()
    expect(writeCalls()).toHaveLength(0)
    fireEvent.click(within(dialog).getByRole('button', { name: 'Unpublish' }))
    await waitFor(() => expect(apiFetch).toHaveBeenCalledWith(`${R}/r1`, { method: 'DELETE' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
  })

  it('shows an unpublish failure inside the confirm dialog and keeps it open', async () => {
    renderList()
    await screen.findByRole('table', { name: 'published domains' })
    apiFetch.mockImplementation((_p: string, o?: { method?: string }) => (o?.method ? Promise.reject(new ApiError(502, 'x', 'PROXY_SYNC_FAILED')) : Promise.resolve(routes)))
    openMenu(await screen.findByRole('button', { name: /More actions/i }))
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Unpublish' }))
    const dialog = await screen.findByRole('dialog')
    fireEvent.click(within(dialog).getByRole('button', { name: 'Unpublish' }))
    expect(await within(dialog).findByText(/could not update the proxy/i)).toBeInTheDocument()
  })

  it('publishes with an auto-detected port: shown read-only and not sent', async () => {
    renderList()
    const dialog = await openPublish()
    fireEvent.click(await within(dialog).findByRole('radio', { name: /web/ }))
    expect(within(dialog).getByText('8080')).toBeInTheDocument()
    expect(within(dialog).queryByRole('combobox', { name: 'Port' })).not.toBeInTheDocument()
    type(within(dialog).getByLabelText('Domain'), 'shop.example.com')
    fireEvent.click(within(dialog).getByRole('button', { name: 'Publish' }))
    await waitFor(() => expect(apiFetch).toHaveBeenCalledWith(R, { method: 'POST', body: { containerName: 'web', hostname: 'shop.example.com' } }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
  })

  it('asks for the port when the container exposes several', async () => {
    renderList()
    const dialog = await openPublish()
    fireEvent.click(await within(dialog).findByRole('radio', { name: /api/ }))
    const port = within(dialog).getByRole('combobox', { name: 'Port' })
    type(within(dialog).getByLabelText('Domain'), 'api.example.com')
    fireEvent.click(within(dialog).getByRole('button', { name: 'Publish' }))
    expect(await within(dialog).findByText('Choose the port the container listens on.')).toBeInTheDocument()
    expect(writeCalls()).toHaveLength(0)
    type(port, '8080')
    fireEvent.click(within(dialog).getByRole('button', { name: 'Publish' }))
    await waitFor(() => expect(apiFetch).toHaveBeenCalledWith(R, { method: 'POST', body: { containerName: 'api', hostname: 'api.example.com', port: 8080 } }))
  })

  it('lets the user type a port when the container exposes none', async () => {
    renderList()
    const dialog = await openPublish()
    fireEvent.click(await within(dialog).findByRole('radio', { name: /worker/ }))
    type(within(dialog).getByLabelText('Domain'), 'w.example.com')
    type(within(dialog).getByLabelText('Port'), '3000')
    fireEvent.click(within(dialog).getByRole('button', { name: 'Publish' }))
    await waitFor(() => expect(apiFetch).toHaveBeenCalledWith(R, { method: 'POST', body: { containerName: 'worker', hostname: 'w.example.com', port: 3000 } }))
  })

  it('validates the domain while typing and does not send an invalid one', async () => {
    renderList()
    const dialog = await openPublish()
    fireEvent.click(await within(dialog).findByRole('radio', { name: /web/ }))
    const domain = within(dialog).getByLabelText('Domain')
    type(domain, 'App.Example.com')
    expect(within(dialog).getByText('Use lower-case letters only.')).toBeInTheDocument()
    type(domain, 'localhost')
    expect(within(dialog).getByText(/full domain name/i)).toBeInTheDocument()
    fireEvent.click(within(dialog).getByRole('button', { name: 'Publish' }))
    expect(writeCalls()).toHaveLength(0)
    type(domain, 'ok.example.com')
    expect(within(dialog).queryByText(/full domain name/i)).not.toBeInTheDocument()
  })

  it('requires a container and a domain', async () => {
    renderList()
    const dialog = await openPublish()
    fireEvent.click(await within(dialog).findByRole('button', { name: 'Publish' }))
    expect(await within(dialog).findByText('Choose the container to publish.')).toBeInTheDocument()
    expect(within(dialog).getByText('Enter the domain name.')).toBeInTheDocument()
    expect(writeCalls()).toHaveLength(0)
  })

  it('shows a taken domain inline at the domain field', async () => {
    renderList()
    const dialog = await openPublish()
    fireEvent.click(await within(dialog).findByRole('radio', { name: /web/ }))
    type(within(dialog).getByLabelText('Domain'), 'app.example.com')
    apiFetch.mockImplementation((_p: string, o?: { method?: string }) => (o?.method ? Promise.reject(new ApiError(409, 'x', 'ROUTE_HOST_TAKEN')) : Promise.resolve(candidates)))
    fireEvent.click(within(dialog).getByRole('button', { name: 'Publish' }))
    expect(await within(dialog).findByText('This domain is already used by another route.')).toBeInTheDocument()
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })

  it('turns a PORT_REQUIRED answer into a port choice', async () => {
    renderList()
    const dialog = await openPublish()
    fireEvent.click(await within(dialog).findByRole('radio', { name: /web/ }))
    type(within(dialog).getByLabelText('Domain'), 'x.example.com')
    apiFetch.mockImplementation((_p: string, o?: { method?: string }) => (o?.method ? Promise.reject(new ApiError(400, 'x', 'PORT_REQUIRED', undefined, ['3000', '9000'])) : Promise.resolve(candidates)))
    fireEvent.click(within(dialog).getByRole('button', { name: 'Publish' }))
    const select = await within(dialog).findByRole('combobox', { name: 'Port' })
    expect(within(select).getByRole('option', { name: '9000' })).toBeInTheDocument()
  })

  it('points to the setup when no proxy exists', async () => {
    renderList()
    const dialog = await openPublish()
    fireEvent.click(await within(dialog).findByRole('radio', { name: /web/ }))
    type(within(dialog).getByLabelText('Domain'), 'x.example.com')
    apiFetch.mockImplementation((_p: string, o?: { method?: string }) => (o?.method ? Promise.reject(new ApiError(409, 'x', 'PROXY_NOT_SET_UP')) : Promise.resolve(candidates)))
    fireEvent.click(within(dialog).getByRole('button', { name: 'Publish' }))
    expect(await within(dialog).findByText(/set up the reverse proxy first/i)).toBeInTheDocument()
  })

  it('filters the containers by the search field', async () => {
    renderList()
    const dialog = await openPublish()
    await within(dialog).findByRole('radio', { name: /web/ })
    type(within(dialog).getByRole('searchbox', { name: 'Search containers' }), 'wor')
    expect(within(dialog).queryByRole('radio', { name: /web/ })).not.toBeInTheDocument()
    expect(within(dialog).getByRole('radio', { name: /worker/ })).toBeInTheDocument()
  })

  it('marks containers that already have a domain', async () => {
    candidates = [candidate({ published: true })]
    renderList()
    const dialog = await openPublish()
    expect(await within(dialog).findByText('Already published')).toBeInTheDocument()
  })

  it('edits the domain and keeps the advanced settings of the route', async () => {
    routes = [route({ advancedActive: true, pathPrefix: '/app', redirectHttps: false, aliases: ['www.example.com'], responseHeaders: { 'X-A': 'b' }, entrypoint: 'web2', certMode: 'ACME_DNS' })]
    renderList()
    await screen.findByRole('table', { name: 'published domains' })
    fireEvent.click(await screen.findByRole('button', { name: /^Edit/ }))
    const dialog = await screen.findByRole('dialog')
    type(within(dialog).getByLabelText('Domain'), 'new.example.com')
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save' }))
    await waitFor(() =>
      expect(apiFetch).toHaveBeenCalledWith(`${R}/r1`, {
        method: 'PUT',
        body: {
          hostname: 'new.example.com',
          containerName: 'web',
          port: 8080,
          aliases: ['www.example.com'],
          backendScheme: 'HTTP',
          pathPrefix: '/app',
          entrypoint: 'web2',
          certMode: 'ACME_DNS',
          certificateId: null,
          redirectHttps: false,
          responseHeaders: { 'X-A': 'b' },
        },
      }),
    )
  })
})

describe('PublishDialog prefill', () => {
  it('starts with the given container and port', async () => {
    const onClose = vi.fn()
    wrap(<PublishDialog hostId="h1" containerName="api" port={8080} onClose={onClose} />)
    const dialog = await screen.findByRole('dialog')
    expect(await within(dialog).findByText('api')).toBeInTheDocument()
    expect(within(dialog).queryByRole('radio')).not.toBeInTheDocument()
    expect(await within(dialog).findByRole('combobox', { name: 'Port' })).toHaveValue('8080')
    type(within(dialog).getByLabelText('Domain'), 'api.example.com')
    fireEvent.click(within(dialog).getByRole('button', { name: 'Publish' }))
    await waitFor(() => expect(apiFetch).toHaveBeenCalledWith(R, { method: 'POST', body: { containerName: 'api', hostname: 'api.example.com', port: 8080 } }))
    await waitFor(() => expect(onClose).toHaveBeenCalled())
  })

  function rejectWrites(error: ApiError) {
    apiFetch.mockImplementation((path: string, o?: { method?: string }) => {
      if (o?.method) return Promise.reject(error)
      if (path === `${R}/candidates`) return Promise.resolve(candidates)
      if (path === '/hosts/h1/proxy/managed') return Promise.resolve({ instance: { type: 'TRAEFIK', defaultCertMode: 'ACME_HTTP' }, setupBlockedReason: null })
      return Promise.resolve(routes)
    })
  }

  it('shows a container error as an alert when the container is locked', async () => {
    wrap(<PublishDialog hostId="h1" containerName="web" onClose={vi.fn()} />)
    const dialog = await screen.findByRole('dialog')
    await within(dialog).findByText('Detected automatically.')
    type(within(dialog).getByLabelText('Domain'), 'x.example.com')
    rejectWrites(new ApiError(404, 'x', 'CONTAINER_NOT_FOUND'))
    fireEvent.click(within(dialog).getByRole('button', { name: 'Publish' }))
    expect(await within(dialog).findByRole('alert')).toHaveTextContent(/container cannot be published/i)
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })

  it('shows a container error as an alert when editing a route whose container is gone', async () => {
    routes = [route({ containerState: null })]
    renderList()
    fireEvent.click(await screen.findByRole('button', { name: /^Edit/ }))
    const dialog = await screen.findByRole('dialog')
    rejectWrites(new ApiError(404, 'x', 'CONTAINER_NOT_FOUND'))
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save' }))
    expect(await within(dialog).findByRole('alert')).toHaveTextContent(/container cannot be published/i)
  })

  it('shows a port error as an alert when the port was auto-detected', async () => {
    wrap(<PublishDialog hostId="h1" containerName="web" onClose={vi.fn()} />)
    const dialog = await screen.findByRole('dialog')
    await within(dialog).findByText('Detected automatically.')
    type(within(dialog).getByLabelText('Domain'), 'x.example.com')
    rejectWrites(new ApiError(400, 'x', 'INVALID_PORT'))
    fireEvent.click(within(dialog).getByRole('button', { name: 'Publish' }))
    expect(await within(dialog).findByRole('alert')).toHaveTextContent(/port between 1 and 65535/i)
  })

  it('saves a CUSTOM-certificate route with a wildcard domain unchanged', async () => {
    routes = [route({ hostname: '*.example.com', certMode: 'CUSTOM', certificateId: 'c1', advancedActive: true })]
    renderList()
    fireEvent.click(await screen.findByRole('button', { name: /^Edit/ }))
    const dialog = await screen.findByRole('dialog')
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(apiFetch).toHaveBeenCalledWith(`${R}/r1`, expect.objectContaining({ method: 'PUT', body: expect.objectContaining({ hostname: '*.example.com', certMode: 'CUSTOM' }) })))
  })
})
