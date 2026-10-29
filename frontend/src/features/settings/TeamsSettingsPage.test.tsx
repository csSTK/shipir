import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, apiFetch } from '../../shared/api/client.ts'
import { TeamsSettingsPage } from './TeamsSettingsPage.tsx'
import { UserAccessDialog } from './UserAccessDialog.tsx'
import type { ManagedUser } from './api.ts'

vi.mock('../../shared/api/client.ts', async (importActual) => ({
  ...(await importActual<typeof import('../../shared/api/client.ts')>()),
  apiFetch: vi.fn(),
}))
const apiFetchMock = vi.mocked(apiFetch)

afterEach(() => {
  cleanup()
  vi.resetAllMocks()
})

const base = { passwordChangeRequired: false, createdAt: '2026-10-01T10:00:00Z', passwordChangedAt: null, disabledAt: null }
const anna: ManagedUser = { ...base, id: 'u2', username: 'anna', role: 'OPERATOR', disabled: false }
const bob: ManagedUser = { ...base, id: 'u3', username: 'bob', role: 'VIEWER', disabled: false }
const ops = { id: 't1', name: 'Ops', memberCount: 1, createdAt: '2026-10-20T10:00:00Z' }
const opsDetail = { id: 't1', name: 'Ops', members: [{ userId: 'u2', username: 'anna' }], createdAt: '2026-10-20T10:00:00Z' }

type Handler = (path: string, method: string, body: unknown) => unknown

let teamGrants: unknown[] = []
let userTeamGrants: unknown[] = []

function mockApi(handler?: Handler, teams = [ops]) {
  teamGrants = []
  userTeamGrants = []
  apiFetchMock.mockImplementation((path: string, options?: { method?: string; body?: unknown }) => {
    const method = options?.method ?? 'GET'
    if (method === 'GET' && path === '/teams') return Promise.resolve(teams)
    if (method === 'GET' && path === '/teams/t1') return Promise.resolve(opsDetail)
    if (method === 'GET' && path === '/users') return Promise.resolve([anna, bob])
    if (method === 'GET' && path === '/hosts') return Promise.resolve([{ id: 'h1', name: 'prod' }])
    if (method === 'GET' && path === '/hosts/h1/managed-deployments') return Promise.resolve([{ id: 'd1', name: 'web' }])
    if (method === 'GET' && path === '/teams/t1/permissions') return Promise.resolve({ grants: teamGrants })
    // inherited access of a user: one request with the teams and their named grants
    if (method === 'GET' && path === '/users/u2/teams') return Promise.resolve({ teams: [{ id: 't1', name: 'Ops', grants: userTeamGrants }] })
    if (method === 'GET' && path === '/users/u3/teams') return Promise.resolve({ teams: [] })
    const result = handler?.(path, method, options?.body)
    return result instanceof Error ? Promise.reject(result) : Promise.resolve(result)
  })
}

function wrap(ui: React.ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={client}>{ui}</QueryClientProvider>)
}

const calls = (method: string) =>
  apiFetchMock.mock.calls.filter(([, o]) => (o as { method?: string } | undefined)?.method === method)

describe('TeamsSettingsPage', () => {
  it('lists teams with member counts and shows an empty state', async () => {
    mockApi()
    wrap(<TeamsSettingsPage />)
    expect(await screen.findByText('Ops')).toBeInTheDocument()
    cleanup()
    mockApi(undefined, [])
    wrap(<TeamsSettingsPage />)
    expect(await screen.findByText('No teams yet.')).toBeInTheDocument()
  })

  it('creates a team and shows a taken name inline', async () => {
    mockApi((_p, method) => (method === 'POST' ? new ApiError(409, 'taken', 'TEAM_NAME_TAKEN') : undefined))
    wrap(<TeamsSettingsPage />)
    await screen.findByText('Ops')
    fireEvent.click(screen.getByRole('button', { name: 'Add team' }))
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Ops' } })
    fireEvent.click(screen.getByRole('button', { name: 'Create team' }))
    expect(await screen.findByText('This team name is already taken.')).toBeInTheDocument()
    expect(calls('POST')[0]).toEqual(['/teams', { method: 'POST', body: { name: 'Ops' } }])
  })

  it('renames a team', async () => {
    mockApi(() => ops)
    wrap(<TeamsSettingsPage />)
    await screen.findByText('Ops')
    fireEvent.click(screen.getByRole('button', { name: 'Rename Ops' }))
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Platform' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(calls('PUT')).toHaveLength(1))
    expect(calls('PUT')[0]).toEqual(['/teams/t1', { method: 'PUT', body: { name: 'Platform' } }])
  })

  it('replaces the members of a team', async () => {
    mockApi(() => opsDetail)
    wrap(<TeamsSettingsPage />)
    await screen.findByText('Ops')
    fireEvent.click(screen.getByRole('button', { name: 'Members of Ops' }))
    const dialog = within(await screen.findByRole('dialog'))
    expect(await dialog.findByLabelText('anna')).toBeChecked()
    expect(dialog.getByLabelText('bob')).not.toBeChecked()
    fireEvent.click(dialog.getByLabelText('bob'))
    fireEvent.click(dialog.getByRole('button', { name: 'Save members' }))
    await waitFor(() => expect(calls('PUT')).toHaveLength(1))
    expect(calls('PUT')[0]).toEqual(['/teams/t1/members', { method: 'PUT', body: { userIds: ['u2', 'u3'] } }])
  })

  it('saves team access through the shared access dialog', async () => {
    mockApi(() => ({ grants: [] }))
    wrap(<TeamsSettingsPage />)
    await screen.findByText('Ops')
    fireEvent.click(screen.getByRole('button', { name: 'Access for Ops' }))
    const dialog = within(await screen.findByRole('dialog'))
    fireEvent.change(await dialog.findByLabelText('Access to prod'), { target: { value: 'VIEW' } })
    fireEvent.click(dialog.getByRole('button', { name: 'Save access' }))
    await waitFor(() => expect(calls('PUT')).toHaveLength(1))
    expect(calls('PUT')[0]).toEqual(['/teams/t1/permissions', { method: 'PUT', body: { grants: [{ hostId: 'h1', level: 'VIEW' }] } }])
  })

  it('deletes after confirmation', async () => {
    mockApi()
    wrap(<TeamsSettingsPage />)
    await screen.findByText('Ops')
    fireEvent.click(screen.getByRole('button', { name: 'Delete Ops' }))
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Delete' }))
    await waitFor(() => expect(calls('DELETE')).toHaveLength(1))
    expect(calls('DELETE')[0][0]).toBe('/teams/t1')
  })
})

describe('UserAccessDialog inherited team access', () => {
  const grants = [
    { hostId: 'h1', hostName: 'prod', deploymentId: null, deploymentName: null, level: 'VIEW' },
    { hostId: null, hostName: null, deploymentId: 'd1', deploymentName: 'web', level: 'OPERATE' },
  ]

  it('shows the grants a member inherits from a team, with names and levels', async () => {
    mockApi((p) => (p === '/users/u2/permissions' ? { grants: [] } : undefined))
    userTeamGrants = grants
    wrap(<UserAccessDialog subject={{ kind: 'user', user: anna }} onClose={() => {}} />)
    const section = within(await screen.findByRole('group', { name: 'Inherited team access' }))
    expect(await section.findByText('Ops')).toBeInTheDocument()
    expect(await section.findByText('prod: View')).toBeInTheDocument()
    expect(await section.findByText('web (deployment): Operate')).toBeInTheDocument()
  })

  it('caps the inherited level to View for a viewer', async () => {
    mockApi((p) => (p === '/users/u2/permissions' ? { grants: [] } : undefined))
    userTeamGrants = grants
    wrap(<UserAccessDialog subject={{ kind: 'user', user: { ...anna, role: 'VIEWER' } }} onClose={() => {}} />)
    const section = within(await screen.findByRole('group', { name: 'Inherited team access' }))
    expect(await section.findByText(/never allow Operate/)).toBeInTheDocument()
  })

  it('does not show teams the user is not a member of', async () => {
    mockApi((p) => (p === '/users/u3/permissions' ? { grants: [] } : undefined))
    userTeamGrants = grants
    wrap(<UserAccessDialog subject={{ kind: 'user', user: bob }} onClose={() => {}} />)
    await screen.findByLabelText('Access to prod')
    await waitFor(() => expect(apiFetchMock).toHaveBeenCalledWith('/users/u3/teams'))
    expect(screen.queryByRole('group', { name: 'Inherited team access' })).not.toBeInTheDocument()
    expect(screen.queryByText('Ops')).not.toBeInTheDocument()
  })

  it('loads the inherited access with a single request instead of one per team', async () => {
    mockApi((p) => (p === '/users/u2/permissions' ? { grants: [] } : undefined))
    userTeamGrants = grants
    wrap(<UserAccessDialog subject={{ kind: 'user', user: anna }} onClose={() => {}} />)
    const section = within(await screen.findByRole('group', { name: 'Inherited team access' }))
    await section.findByText('prod: View')
    const paths = apiFetchMock.mock.calls.map(([path]) => path)
    expect(paths.filter((p) => p === '/users/u2/teams')).toHaveLength(1)
    expect(paths.filter((p) => p.startsWith('/teams'))).toEqual([])
  })
})
