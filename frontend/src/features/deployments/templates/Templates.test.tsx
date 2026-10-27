import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../../shared/api/client.ts'
import { AddDeploymentDialog } from '../AddDeploymentDialog.tsx'

const apiFetch = vi.fn()
vi.mock('../../../shared/api/client.ts', async (importActual) => ({
  ...(await importActual<typeof import('../../../shared/api/client.ts')>()),
  apiFetch: (...args: unknown[]) => apiFetch(...args),
}))

afterEach(() => {
  cleanup()
  apiFetch.mockReset()
})

const templates = [
  {
    id: 'nginx',
    name: 'Nginx',
    description: 'Web server',
    logo: 'nginx',
    env: [],
    spec: { services: [{ name: 'web', image: 'nginx:1.27', ports: [{ host: 8080, container: 80, protocol: 'tcp' }] }], networks: [], volumes: [] },
  },
  {
    id: 'wordpress',
    name: 'WordPress',
    description: 'Blog engine with MySQL',
    logo: 'wordpress',
    env: [{ key: 'DB_PASSWORD', description: 'Database password', secret: true }],
    spec: {
      services: [
        { name: 'wp', image: 'wordpress:6', environment: [{ key: 'WORDPRESS_DB_PASSWORD', value: null, ref: 'DB_PASSWORD' }], volumes: [{ source: 'wpdata', target: '/var/www/html', readOnly: false }], dependsOn: ['db'] },
        { name: 'db', image: 'mysql:8' },
      ],
      networks: [],
      volumes: [{ name: 'wpdata', driver: null, external: false }],
    },
  },
]

function serve(list: unknown = templates, ports: unknown = { used: [], suggestions: [{ preferred: 8080, free: 8080 }] }) {
  apiFetch.mockImplementation((path: string) => {
    if (path.startsWith('/hosts/h1/ports/free')) return ports instanceof Error ? Promise.reject(ports) : Promise.resolve(ports)
    if (path === '/auth/me') return Promise.resolve({ username: 'u', role: 'OPERATOR', setupRequired: false })
    if (path === '/templates') return list instanceof Error ? Promise.reject(list) : Promise.resolve(list)
    return Promise.reject(new ApiError(404, 'x', 'NOT_FOUND'))
  })
}

function open() {
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter>
        <AddDeploymentDialog hostId="h1" onClose={() => {}} />
      </MemoryRouter>
    </QueryClientProvider>,
  )
  fireEvent.click(screen.getByRole('radio', { name: 'From template' }))
}

describe('template picker', () => {
  it('lists templates and filters by search', async () => {
    serve()
    open()
    expect(await screen.findByRole('button', { name: /WordPress/ })).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Search templates'), { target: { value: 'blog' } })
    expect(screen.queryByRole('button', { name: /Nginx/ })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: /WordPress/ })).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Search templates'), { target: { value: 'zzz' } })
    expect(screen.getByText('No template matches your search.')).toBeInTheDocument()
  })

  it('shows an error with retry', async () => {
    serve(new ApiError(500, 'boom', 'X'))
    open()
    expect(await screen.findByRole('alert')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument()
  })

  it('fills the wizard from the chosen template and pre-creates env rows', async () => {
    serve()
    open()
    fireEvent.click(await screen.findByRole('button', { name: /WordPress/ }))
    expect(screen.getByRole('radio', { name: 'Form wizard' })).toBeChecked()
    expect(screen.getByLabelText('Name', { exact: true })).toHaveValue('wordpress')
    expect(screen.getByLabelText('Service 1 name')).toHaveValue('wp')
    expect(screen.getByLabelText('Service 1 image')).toHaveValue('wordpress:6')
    expect(screen.getByLabelText('Service 2 image')).toHaveValue('mysql:8')
    expect(screen.getByLabelText('Service 1 env 1 reference')).toHaveValue('DB_PASSWORD')
    expect(screen.getByText(/Database password/)).toBeInTheDocument()
    // The env editor (YAML mode) already holds the row for the hinted variable.
    fireEvent.click(screen.getByRole('radio', { name: 'YAML' }))
    await waitFor(() => expect(screen.getAllByDisplayValue('DB_PASSWORD').length).toBeGreaterThan(0))
  })

  it('keeps a name the user already typed', async () => {
    serve()
    open()
    fireEvent.change(screen.getByLabelText('Name', { exact: true }), { target: { value: 'mysite' } })
    fireEvent.click(await screen.findByRole('button', { name: /Nginx/ }))
    expect(screen.getByLabelText('Name', { exact: true })).toHaveValue('mysite')
    expect(within(await screen.findByRole('group', { name: 'Service 1' })).getByLabelText('Service 1 image')).toHaveValue('nginx:1.27')
  })

  it('replaces a clashing host port, shows a notice and leaves the port editable', async () => {
    serve(templates, { used: [{ port: 8080, protocol: 'tcp' }], suggestions: [{ preferred: 8080, free: 8083 }] })
    open()
    fireEvent.click(await screen.findByRole('button', { name: /Nginx/ }))
    expect(await screen.findByText('Port 8080 was taken, using 8083.')).toBeInTheDocument()
    expect(apiFetch).toHaveBeenCalledWith('/hosts/h1/ports/free?preferred=8080')
    const host = screen.getByLabelText('Service 1 port 1 host')
    expect(host).toHaveValue('8083')
    fireEvent.change(host, { target: { value: '9000' } })
    expect(host).toHaveValue('9000')
  })

  it('leaves non-clashing ports untouched without a notice', async () => {
    serve()
    open()
    fireEvent.click(await screen.findByRole('button', { name: /Nginx/ }))
    expect(await screen.findByLabelText('Service 1 port 1 host')).toHaveValue('8080')
    expect(screen.queryByText(/was taken/)).not.toBeInTheDocument()
  })

  it('falls back to the template defaults when the ports lookup fails', async () => {
    serve(templates, new ApiError(500, 'boom', 'X'))
    open()
    fireEvent.click(await screen.findByRole('button', { name: /Nginx/ }))
    expect(await screen.findByLabelText('Service 1 port 1 host')).toHaveValue('8080')
    expect(screen.getByRole('radio', { name: 'Form wizard' })).toBeChecked()
    expect(screen.queryByText(/was taken/)).not.toBeInTheDocument()
  })

  it('clears the checking hint when a later pick without ports wins over a slow one', async () => {
    serve()
    let release: (v: unknown) => void = () => {}
    const base = apiFetch.getMockImplementation()!
    apiFetch.mockImplementation((path: string) =>
      path.startsWith('/hosts/h1/ports/free') ? new Promise((r) => (release = r)) : base(path),
    )
    open()
    fireEvent.click(await screen.findByRole('button', { name: /Nginx/ }))
    expect(await screen.findByText('Checking free ports…')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /WordPress/ }))
    expect(await screen.findByLabelText('Service 1 image')).toHaveValue('wordpress:6')
    release({ used: [], suggestions: [{ preferred: 8080, free: 8083 }] })
    fireEvent.click(screen.getByRole('radio', { name: 'From template' }))
    await waitFor(() => expect(screen.queryByText('Checking free ports…')).not.toBeInTheDocument())
    fireEvent.click(screen.getByRole('radio', { name: 'Form wizard' }))
    expect(screen.getByLabelText('Service 1 image')).toHaveValue('wordpress:6')
    expect(screen.queryByText(/was taken/)).not.toBeInTheDocument()
  })
})
