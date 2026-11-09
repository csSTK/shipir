import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../../shared/api/client.ts'
import { InstallTemplateDialog } from './InstallTemplateDialog.tsx'
import { generateSecret, suggestName } from './install.ts'
import type { AppTemplate } from './api.ts'

const apiFetch = vi.fn()
vi.mock('../../../shared/api/client.ts', async (importActual) => ({
  ...(await importActual<typeof import('../../../shared/api/client.ts')>()),
  apiFetch: (...args: unknown[]) => apiFetch(...args),
}))

afterEach(() => {
  cleanup()
  apiFetch.mockReset()
})

const wordpress: AppTemplate = {
  id: 'wordpress',
  name: 'WordPress',
  description: 'Blog engine',
  env: [
    { key: 'DB_PASSWORD', description: 'Database password', secret: true, generate: true },
    { key: 'DB_USER', description: 'Database user', secret: false, defaultValue: 'wp' },
  ],
  spec: {
    services: [
      {
        name: 'wp',
        image: 'wordpress:6',
        ports: [{ host: 8080, container: 80 }],
        environment: [{ key: 'WORDPRESS_DB_PASSWORD', value: null, ref: 'DB_PASSWORD' }],
      },
    ],
    networks: [],
    volumes: [],
  },
}

interface Setup {
  existing?: string[]
  free?: number
  create?: unknown
  deploy?: unknown
}

function serve({ existing = [], free = 8080, create, deploy }: Setup = {}) {
  apiFetch.mockImplementation((path: string, init?: { method?: string; body?: unknown }) => {
    if (path.startsWith('/hosts/h1/ports/free')) return Promise.resolve({ used: [], suggestions: [{ preferred: 8080, free }] })
    if (path === '/hosts/h1/managed-deployments' && !init?.method) return Promise.resolve(existing.map((name) => ({ id: name, name })))
    if (path === '/compose/generate') return Promise.resolve({ yaml: 'services: {}\n' })
    if (path === '/hosts/h1/managed-deployments' && init?.method === 'POST') return create instanceof Error ? Promise.reject(create) : Promise.resolve(create ?? { id: 's1' })
    if (path === '/hosts/h1/managed-deployments/s1/deploy') return deploy instanceof Error ? Promise.reject(deploy) : Promise.resolve({ runId: 'r1', versionId: 'v1' })
    return Promise.reject(new ApiError(404, 'x', 'NOT_FOUND'))
  })
}

function Where() {
  return <p data-testid="where">{useLocation().pathname}</p>
}

function open(onAdvanced = vi.fn(), template: AppTemplate = wordpress, onClose: () => void = () => {}) {
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter>
        <InstallTemplateDialog hostId="h1" template={template} onClose={onClose} onAdvanced={onAdvanced} />
        <Routes>
          <Route path="*" element={<Where />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
  return onAdvanced
}

const calls = (path: string, method?: string) => apiFetch.mock.calls.filter((c) => c[0] === path && (c[1]?.method ?? undefined) === method)

describe('helpers', () => {
  it('generateSecret returns distinct, non-empty, url-safe values', () => {
    const a = generateSecret()
    expect(a.length).toBeGreaterThanOrEqual(32)
    expect(a).toMatch(/^[A-Za-z0-9]+$/)
    expect(generateSecret()).not.toBe(a)
  })

  it('suggestName appends a counter until the name is free', () => {
    expect(suggestName('wordpress', [])).toBe('wordpress')
    expect(suggestName('wordpress', ['wordpress'])).toBe('wordpress-2')
    expect(suggestName('wordpress', ['wordpress', 'wordpress-2'])).toBe('wordpress-3')
  })
})

describe('InstallTemplateDialog', () => {
  it('installs with untouched defaults: create, deploy, navigate', async () => {
    serve()
    open()
    await waitFor(() => expect(screen.getByLabelText('Name')).toHaveValue('wordpress'))
    expect(screen.getByLabelText('DB_USER')).toHaveValue('wp')
    fireEvent.click(screen.getByRole('button', { name: 'Install' }))
    await waitFor(() => expect(screen.getByTestId('where')).toHaveTextContent('/environments/h1/deployments/s1'))
    const body = calls('/hosts/h1/managed-deployments', 'POST')[0][1].body
    expect(body.name).toBe('wordpress')
    expect(body.yaml).toBe('services: {}\n')
    expect(body.env).toEqual([
      { key: 'DB_PASSWORD', value: expect.stringMatching(/^[A-Za-z0-9]{32,}$/) },
      { key: 'DB_USER', value: 'wp' },
    ])
    expect(calls('/hosts/h1/managed-deployments/s1/deploy', 'POST')).toHaveLength(1)
  })

  it('sends the target host to compose generate so the managed-proxy rules apply', async () => {
    serve()
    open()
    await waitFor(() => expect(screen.getByLabelText('Name')).toHaveValue('wordpress'))
    fireEvent.click(screen.getByRole('button', { name: 'Install' }))
    await waitFor(() => expect(calls('/compose/generate', 'POST')).toHaveLength(1))
    expect(calls('/compose/generate', 'POST')[0][1].body.hostId).toBe('h1')
  })

  it('shows a generated secret that can be revealed and regenerated', async () => {
    serve()
    open()
    const field = await screen.findByLabelText('DB_PASSWORD')
    expect(field).toHaveAttribute('type', 'password')
    const first = (field as HTMLInputElement).value
    expect(first).not.toBe('')
    fireEvent.click(screen.getByRole('button', { name: 'Show DB_PASSWORD' }))
    expect(field).toHaveAttribute('type', 'text')
    fireEvent.click(screen.getByRole('button', { name: 'Generate a new DB_PASSWORD' }))
    expect((field as HTMLInputElement).value).not.toBe(first)
    expect((field as HTMLInputElement).value).not.toBe('')
  })

  it('uses the edited name, port and env values', async () => {
    serve()
    open()
    await waitFor(() => expect(screen.getByLabelText('Name')).toHaveValue('wordpress'))
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'blog' } })
    fireEvent.change(screen.getByLabelText('DB_USER'), { target: { value: 'me' } })
    fireEvent.change(await screen.findByLabelText('Host port for wp (container port 80)'), { target: { value: '9000' } })
    apiFetch.mockImplementation(((impl) => (path: string, init: unknown) => (path.startsWith('/hosts/h1/ports/free') ? Promise.resolve({ used: [], suggestions: [{ preferred: 9000, free: 9000 }] }) : impl(path, init)))(apiFetch.getMockImplementation()!))
    fireEvent.click(screen.getByRole('button', { name: 'Install' }))
    await waitFor(() => expect(calls('/hosts/h1/managed-deployments', 'POST')).toHaveLength(1))
    expect(calls('/hosts/h1/managed-deployments', 'POST')[0][1].body.name).toBe('blog')
    expect(calls('/hosts/h1/managed-deployments', 'POST')[0][1].body.env).toContainEqual({ key: 'DB_USER', value: 'me' })
    const spec = calls('/compose/generate', 'POST')[0][1].body.spec
    expect(spec.services[0].ports[0].host).toBe(9000)
  })

  it('suggests a unique name and prefills a free port instead of the taken one', async () => {
    serve({ existing: ['wordpress'], free: 8083 })
    open()
    await waitFor(() => expect(screen.getByLabelText('Name')).toHaveValue('wordpress-2'))
    expect(await screen.findByLabelText('Host port for wp (container port 80)')).toHaveValue(8083)
  })

  it('keeps a taken DNS port 53 with a visible warning and edits tcp/udp together', async () => {
    const pihole: AppTemplate = {
      ...wordpress,
      id: 'pihole',
      env: [],
      spec: { services: [{ name: 'dns', image: 'pihole/pihole', ports: [{ host: 53, container: 53, protocol: 'tcp' }, { host: 53, container: 53, protocol: 'udp' }] }], networks: [], volumes: [] },
    }
    apiFetch.mockImplementation((path: string) => {
      if (path.startsWith('/hosts/h1/ports/free')) return Promise.resolve({ used: [{ port: 53, protocol: 'udp' }], suggestions: [{ preferred: 53, free: 5353 }] })
      if (path === '/hosts/h1/managed-deployments') return Promise.resolve([])
      return Promise.reject(new ApiError(404, 'x', 'NOT_FOUND'))
    })
    open(vi.fn(), pihole)
    const ports = await screen.findAllByLabelText('Host port for dns (container port 53)')
    await waitFor(() => expect(ports[0]).toHaveAccessibleDescription(/Port 53 is taken on this host and was kept/))
    expect(ports.map((p) => (p as HTMLInputElement).value)).toEqual(['53', '53'])
    fireEvent.change(ports[0], { target: { value: '5353' } })
    expect(ports.map((p) => (p as HTMLInputElement).value)).toEqual(['5353', '5353'])
    expect(ports[0]).not.toHaveAccessibleDescription(/was kept/)
  })

  it('shows a taken name inline on the name field and creates nothing', async () => {
    serve({ create: new ApiError(409, 'taken', 'DEPLOYMENT_NAME_TAKEN') })
    open()
    await waitFor(() => expect(screen.getByLabelText('Name')).toHaveValue('wordpress'))
    fireEvent.click(screen.getByRole('button', { name: 'Install' }))
    const name = screen.getByLabelText('Name')
    await waitFor(() => expect(name).toBeInvalid())
    expect(name).toHaveAccessibleDescription('A deployment with this name already exists in this environment.')
    expect(calls('/hosts/h1/managed-deployments/s1/deploy', 'POST')).toHaveLength(0)
  })

  it('flags a port that became taken inline on the port field', async () => {
    serve()
    open()
    const port = await screen.findByLabelText('Host port for wp (container port 80)')
    apiFetch.mockImplementation(((impl) => (path: string, init: unknown) => (path.startsWith('/hosts/h1/ports/free') ? Promise.resolve({ used: [{ port: 8080, protocol: 'tcp' }], suggestions: [{ preferred: 8080, free: 8085 }] }) : impl(path, init)))(apiFetch.getMockImplementation()!))
    fireEvent.click(screen.getByRole('button', { name: 'Install' }))
    await waitFor(() => expect(port).toBeInvalid())
    expect(port).toHaveAccessibleDescription('Port 8080 is taken. Try 8085.')
    expect(calls('/hosts/h1/managed-deployments', 'POST')).toHaveLength(0)
  })

  it('explains a missing server encryption key', async () => {
    serve({ create: new ApiError(409, 'x', 'ENCRYPTION_KEY_MISSING') })
    open()
    await waitFor(() => expect(screen.getByLabelText('Name')).toHaveValue('wordpress'))
    fireEvent.click(screen.getByRole('button', { name: 'Install' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('The server has no encryption key')
  })

  it('retries only the deploy when it failed after the deployment was created', async () => {
    serve({ deploy: new ApiError(409, 'busy', 'DEPLOYMENT_IN_PROGRESS') })
    open()
    await waitFor(() => expect(screen.getByLabelText('Name')).toHaveValue('wordpress'))
    fireEvent.click(screen.getByRole('button', { name: 'Install' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('already in progress')
    expect(calls('/hosts/h1/managed-deployments', 'POST')).toHaveLength(1)
    apiFetch.mockClear()
    serve()
    fireEvent.click(screen.getByRole('button', { name: 'Install' }))
    await waitFor(() => expect(screen.getByTestId('where')).toHaveTextContent('/environments/h1/deployments/s1'))
    expect(calls('/hosts/h1/managed-deployments', 'POST')).toHaveLength(0)
  })

  it('Advanced hands the edited values to the wizard without creating anything', async () => {
    serve()
    const onAdvanced = open()
    await waitFor(() => expect(screen.getByLabelText('Name')).toHaveValue('wordpress'))
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'blog' } })
    fireEvent.change(await screen.findByLabelText('Host port for wp (container port 80)'), { target: { value: '9000' } })
    fireEvent.click(screen.getByRole('button', { name: 'Advanced' }))
    expect(onAdvanced).toHaveBeenCalledTimes(1)
    const seed = onAdvanced.mock.calls[0][0]
    expect(seed.name).toBe('blog')
    expect(seed.state.services[0].ports[0].host).toBe('9000')
    expect(seed.env).toContainEqual({ key: 'DB_USER', value: 'wp' })
    expect(seed.env.find((e: { key: string }) => e.key === 'DB_PASSWORD').value).not.toBe('')
    expect(calls('/hosts/h1/managed-deployments', 'POST')).toHaveLength(0)
  })

  it('cannot be closed (Cancel, Esc) while create/deploy is in flight, and can be afterwards', async () => {
    serve()
    let release: (v: unknown) => void = () => {}
    const base = apiFetch.getMockImplementation()!
    apiFetch.mockImplementation((path: string, init?: { method?: string }) =>
      path === '/hosts/h1/managed-deployments' && init?.method === 'POST' ? new Promise((r) => (release = r)) : base(path, init),
    )
    const onClose = vi.fn()
    open(vi.fn(), wordpress, onClose)
    await waitFor(() => expect(screen.getByLabelText('Name')).toHaveValue('wordpress'))
    fireEvent.click(screen.getByRole('button', { name: 'Install' }))
    await waitFor(() => expect(calls('/hosts/h1/managed-deployments', 'POST')).toHaveLength(1))
    expect(screen.getByRole('button', { name: 'Cancel' })).toBeDisabled()
    fireEvent.keyDown(screen.getByRole('dialog'), { key: 'Escape' })
    fireEvent.click(screen.getByRole('button', { name: 'Close' }))
    expect(onClose).not.toHaveBeenCalled()
    release({ id: 's1' })
    await waitFor(() => expect(screen.getByTestId('where')).toHaveTextContent('/environments/h1/deployments/s1'))
  })

  it('can be cancelled when idle', async () => {
    serve()
    const onClose = vi.fn()
    open(vi.fn(), wordpress, onClose)
    fireEvent.click(await screen.findByRole('button', { name: 'Cancel' }))
    expect(onClose).toHaveBeenCalledTimes(1)
  })
})
