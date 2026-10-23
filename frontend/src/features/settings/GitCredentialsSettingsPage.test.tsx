import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, apiFetch } from '../../shared/api/client.ts'
import { GitCredentialsSettingsPage } from './GitCredentialsSettingsPage.tsx'
import type { GitCredential } from './api.ts'

vi.mock('../../shared/api/client.ts', async (importActual) => ({
  ...(await importActual<typeof import('../../shared/api/client.ts')>()),
  apiFetch: vi.fn(),
}))
const apiFetchMock = vi.mocked(apiFetch)

afterEach(() => {
  cleanup()
  vi.resetAllMocks()
})

const cred: GitCredential = {
  id: 'g1',
  name: 'GitHub work',
  username: 'octocat',
  tokenHint: '…a1b2',
  createdAt: '2026-10-23T10:00:00Z',
  updatedAt: '2026-10-23T10:00:00Z',
}

function renderPage(handler?: (path: string, method: string, body: unknown) => unknown, list: GitCredential[] = [cred]) {
  apiFetchMock.mockImplementation((path: string, options?: { method?: string; body?: unknown }) => {
    const method = options?.method ?? 'GET'
    if (method === 'GET' && path === '/git/credentials') return Promise.resolve(list)
    const result = handler?.(path, method, options?.body)
    return result instanceof Error ? Promise.reject(result) : Promise.resolve(result)
  })
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <GitCredentialsSettingsPage />
    </QueryClientProvider>,
  )
}

const calls = (method: string) =>
  apiFetchMock.mock.calls.filter(([, o]) => (o as { method?: string } | undefined)?.method === method)

function fillCreate(name: string, username: string, token: string) {
  fireEvent.click(screen.getByRole('button', { name: 'Add credential' }))
  fireEvent.change(screen.getByLabelText('Name'), { target: { value: name } })
  fireEvent.change(screen.getByLabelText('Username'), { target: { value: username } })
  fireEvent.change(screen.getByLabelText('Access token'), { target: { value: token } })
  fireEvent.click(screen.getByRole('button', { name: 'Save' }))
}

describe('GitCredentialsSettingsPage', () => {
  it('lists credentials with a token hint only', async () => {
    renderPage()
    expect(await screen.findByText('GitHub work')).toBeInTheDocument()
    expect(screen.getByText(/octocat/)).toBeInTheDocument()
    expect(screen.getByText(/a1b2/)).toBeInTheDocument()
  })

  it('shows an empty state', async () => {
    renderPage(undefined, [])
    expect(await screen.findByText('No credentials yet.')).toBeInTheDocument()
  })

  it('creates a credential', async () => {
    renderPage(() => ({ ...cred, id: 'g2' }))
    await screen.findByText('GitHub work')
    fillCreate('Gitea', 'me', 'secret-token')
    await waitFor(() => expect(calls('POST')).toHaveLength(1))
    expect(calls('POST')[0]).toEqual([
      '/git/credentials',
      { method: 'POST', body: { name: 'Gitea', username: 'me', token: 'secret-token' } },
    ])
  })

  it('requires a token on create', async () => {
    renderPage()
    await screen.findByText('GitHub work')
    fillCreate('Gitea', 'me', '')
    expect(await screen.findByText('Enter the access token.')).toBeInTheDocument()
    expect(calls('POST')).toHaveLength(0)
  })

  it('edits without a token when the field stays empty, and never pre-fills it', async () => {
    renderPage(() => cred)
    await screen.findByText('GitHub work')
    fireEvent.click(screen.getByRole('button', { name: 'Edit GitHub work' }))
    const token = screen.getByLabelText('Access token') as HTMLInputElement
    expect(token.value).toBe('')
    expect(token.type).toBe('password')
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Renamed' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(calls('PUT')).toHaveLength(1))
    const body = (calls('PUT')[0][1] as { body: Record<string, unknown> }).body
    expect(calls('PUT')[0][0]).toBe('/git/credentials/g1')
    expect(body).toEqual({ name: 'Renamed', username: 'octocat' })
    expect('token' in body).toBe(false)
  })

  it('shows a taken name inline', async () => {
    renderPage((_p, method) => (method === 'POST' ? new ApiError(409, 'taken', 'CREDENTIAL_NAME_TAKEN') : undefined))
    await screen.findByText('GitHub work')
    fillCreate('GitHub work', 'me', 't')
    expect(await screen.findByText('This name is already in use.')).toBeInTheDocument()
  })

  it('hints at the missing encryption key', async () => {
    renderPage((_p, method) => (method === 'POST' ? new ApiError(409, 'no key', 'ENCRYPTION_KEY_MISSING') : undefined))
    await screen.findByText('GitHub work')
    fillCreate('X', 'me', 't')
    expect(await screen.findByText(/SHIPIR_ENCRYPTION_KEY/)).toBeInTheDocument()
  })

  it('deletes after confirmation', async () => {
    renderPage()
    await screen.findByText('GitHub work')
    fireEvent.click(screen.getByRole('button', { name: 'Delete GitHub work' }))
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Delete' }))
    await waitFor(() => expect(calls('DELETE')).toHaveLength(1))
    expect(calls('DELETE')[0][0]).toBe('/git/credentials/g1')
  })

  it('explains that a credential in use cannot be deleted', async () => {
    renderPage((_p, method) => (method === 'DELETE' ? new ApiError(409, 'in use', 'CREDENTIAL_IN_USE') : undefined))
    await screen.findByText('GitHub work')
    fireEvent.click(screen.getByRole('button', { name: 'Delete GitHub work' }))
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Delete' }))
    expect(await screen.findByText(/still used by one or more stacks/)).toBeInTheDocument()
  })

  it('tests the connection and shows each outcome', async () => {
    let status = 'OK'
    renderPage((path) => (path.endsWith('/test') ? { status } : undefined))
    await screen.findByText('GitHub work')
    fireEvent.click(screen.getByRole('button', { name: 'Test GitHub work' }))
    fireEvent.change(screen.getByLabelText('Repository URL'), { target: { value: 'https://github.com/a/b.git' } })
    fireEvent.click(screen.getByRole('button', { name: 'Run test' }))
    expect(await screen.findByText('Connection successful.')).toBeInTheDocument()
    expect(calls('POST')[0]).toEqual([
      '/git/credentials/g1/test',
      { method: 'POST', body: { repoUrl: 'https://github.com/a/b.git' } },
    ])
    status = 'AUTH_FAILED'
    fireEvent.click(screen.getByRole('button', { name: 'Run test' }))
    expect(await screen.findByText(/rejected the username or token/)).toBeInTheDocument()
    status = 'NOT_FOUND'
    fireEvent.click(screen.getByRole('button', { name: 'Run test' }))
    expect(await screen.findByText(/repository was not found/)).toBeInTheDocument()
    status = 'UNREACHABLE'
    fireEvent.click(screen.getByRole('button', { name: 'Run test' }))
    expect(await screen.findByText(/could not be reached/)).toBeInTheDocument()
  })

  it('shows an invalid repository URL inline', async () => {
    renderPage((path) => (path.endsWith('/test') ? new ApiError(400, 'bad', 'INVALID_REPO_URL') : undefined))
    await screen.findByText('GitHub work')
    fireEvent.click(screen.getByRole('button', { name: 'Test GitHub work' }))
    fireEvent.change(screen.getByLabelText('Repository URL'), { target: { value: 'ftp://x' } })
    fireEvent.click(screen.getByRole('button', { name: 'Run test' }))
    expect(await screen.findByText(/not a valid https repository URL/)).toBeInTheDocument()
  })
})
