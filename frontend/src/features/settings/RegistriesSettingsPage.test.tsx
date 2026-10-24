import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, apiFetch } from '../../shared/api/client.ts'
import { RegistriesSettingsPage } from './RegistriesSettingsPage.tsx'
import type { RegistryCredential } from './api.ts'

vi.mock('../../shared/api/client.ts', async (importActual) => ({
  ...(await importActual<typeof import('../../shared/api/client.ts')>()),
  apiFetch: vi.fn(),
}))
const apiFetchMock = vi.mocked(apiFetch)

afterEach(() => {
  cleanup()
  vi.resetAllMocks()
})

const cred: RegistryCredential = {
  id: 'r1',
  name: 'GHCR',
  registryHost: 'ghcr.io',
  username: 'octocat',
  secretHint: '…a1b2',
  createdAt: '2026-10-24T10:00:00Z',
  updatedAt: '2026-10-24T10:00:00Z',
}

function renderPage(handler?: (path: string, method: string, body: unknown) => unknown, list: RegistryCredential[] = [cred]) {
  apiFetchMock.mockImplementation((path: string, options?: { method?: string; body?: unknown }) => {
    const method = options?.method ?? 'GET'
    if (method === 'GET' && path === '/registries') return Promise.resolve(list)
    const result = handler?.(path, method, options?.body)
    return result instanceof Error ? Promise.reject(result) : Promise.resolve(result)
  })
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <RegistriesSettingsPage />
    </QueryClientProvider>,
  )
}

const calls = (method: string) =>
  apiFetchMock.mock.calls.filter(([, o]) => (o as { method?: string } | undefined)?.method === method)

function fillCreate(name: string, host: string, username: string, secret: string) {
  fireEvent.click(screen.getByRole('button', { name: 'Add registry' }))
  fireEvent.change(screen.getByLabelText('Name'), { target: { value: name } })
  fireEvent.change(screen.getByLabelText('Registry host'), { target: { value: host } })
  fireEvent.change(screen.getByLabelText('Username'), { target: { value: username } })
  fireEvent.change(screen.getByLabelText('Password or token'), { target: { value: secret } })
  fireEvent.click(screen.getByRole('button', { name: 'Save' }))
}

describe('RegistriesSettingsPage', () => {
  it('lists registries with a secret hint only', async () => {
    renderPage()
    expect(await screen.findByText('GHCR')).toBeInTheDocument()
    expect(screen.getByText(/ghcr\.io/)).toBeInTheDocument()
    expect(screen.getByText(/octocat/)).toBeInTheDocument()
    expect(screen.getByText(/a1b2/)).toBeInTheDocument()
  })

  it('shows an empty state', async () => {
    renderPage(undefined, [])
    expect(await screen.findByText('No registries yet.')).toBeInTheDocument()
  })

  it('creates a registry', async () => {
    renderPage(() => ({ ...cred, id: 'r2' }))
    await screen.findByText('GHCR')
    fillCreate('Private', 'registry.example.com:5000', 'me', 'pw')
    await waitFor(() => expect(calls('POST')).toHaveLength(1))
    expect(calls('POST')[0]).toEqual([
      '/registries',
      { method: 'POST', body: { name: 'Private', registryHost: 'registry.example.com:5000', username: 'me', secret: 'pw' } },
    ])
  })

  it('requires a secret on create', async () => {
    renderPage()
    await screen.findByText('GHCR')
    fillCreate('Private', 'ghcr.io', 'me', '')
    expect(await screen.findByText('Enter the password or token.')).toBeInTheDocument()
    expect(calls('POST')).toHaveLength(0)
  })

  it('edits without a secret when the field stays empty, and never pre-fills it', async () => {
    renderPage(() => cred)
    await screen.findByText('GHCR')
    fireEvent.click(screen.getByRole('button', { name: 'Edit GHCR' }))
    const secret = screen.getByLabelText('Password or token') as HTMLInputElement
    expect(secret.value).toBe('')
    expect(secret.type).toBe('password')
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Renamed' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(calls('PUT')).toHaveLength(1))
    const body = (calls('PUT')[0][1] as { body: Record<string, unknown> }).body
    expect(calls('PUT')[0][0]).toBe('/registries/r1')
    expect(body).toEqual({ name: 'Renamed', registryHost: 'ghcr.io', username: 'octocat' })
    expect('secret' in body).toBe(false)
  })

  it.each([
    ['REGISTRY_NAME_TAKEN', 'This name is already in use.'],
    ['REGISTRY_HOST_TAKEN', 'A credential for this registry host already exists.'],
    ['INVALID_REGISTRY_HOST', 'This is not a valid registry host'],
    ['ENCRYPTION_KEY_MISSING', 'SHIPIR_ENCRYPTION_KEY'],
  ])('shows %s inline', async (code, text) => {
    renderPage((_p, method) => (method === 'POST' ? new ApiError(409, 'x', code) : undefined))
    await screen.findByText('GHCR')
    fillCreate('X', 'ghcr.io', 'me', 't')
    expect(await screen.findByText(new RegExp(text.replace(/[.()]/g, '\\$&')))).toBeInTheDocument()
  })

  it('deletes after confirmation', async () => {
    renderPage()
    await screen.findByText('GHCR')
    fireEvent.click(screen.getByRole('button', { name: 'Delete GHCR' }))
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Delete' }))
    await waitFor(() => expect(calls('DELETE')).toHaveLength(1))
    expect(calls('DELETE')[0][0]).toBe('/registries/r1')
  })

  it('tests the login and shows each outcome', async () => {
    let status = 'OK'
    renderPage((path) => (path.endsWith('/test') ? { status } : undefined))
    await screen.findByText('GHCR')
    fireEvent.click(screen.getByRole('button', { name: 'Test GHCR' }))
    fireEvent.click(screen.getByRole('button', { name: 'Run test' }))
    expect(await screen.findByText('Login successful.')).toBeInTheDocument()
    expect(calls('POST')[0]).toEqual(['/registries/r1/test', { method: 'POST' }])
    status = 'AUTH_FAILED'
    fireEvent.click(screen.getByRole('button', { name: 'Run test' }))
    expect(await screen.findByText(/rejected the username or secret/)).toBeInTheDocument()
    status = 'UNREACHABLE'
    fireEvent.click(screen.getByRole('button', { name: 'Run test' }))
    expect(await screen.findByText(/could not be reached/)).toBeInTheDocument()
  })
})
