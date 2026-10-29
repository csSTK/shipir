import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../shared/api/client.ts'
import { AddDeploymentDialog } from './AddDeploymentDialog.tsx'
import { deploymentCardAnswer } from '../../test/deploymentCards.ts'
import { DeploymentDetailPage } from './DeploymentDetailPage.tsx'

vi.mock('../../shared/realtime/stomp.ts', () => ({ subscribeToTopic: () => () => {} }))

const apiFetch = vi.fn()
vi.mock('../../shared/api/client.ts', async (importActual) => ({
  ...(await importActual<typeof import('../../shared/api/client.ts')>()),
  apiFetch: (...args: unknown[]) => apiFetch(...args),
}))

afterEach(() => {
  cleanup()
  apiFetch.mockReset()
})

const BASE = '/hosts/h1/managed-deployments'
const credentials = [{ id: 'c1', name: 'GitHub bot', username: 'bot', tokenHint: '…abcd', createdAt: '', updatedAt: '' }]

const git = {
  repoUrl: 'https://github.com/acme/shop.git',
  ref: 'main',
  composePath: 'deploy/compose.yaml',
  credentialId: 'c1',
  lastCommitSha: 'abcdef1234567',
  autoUpdateEnabled: false,
  autoUpdateIntervalSeconds: 300,
  lastPolledAt: null,
  lastPollError: null,
}
const gitDeployment = {
  id: 's1',
  name: 'shop',
  status: 'RUNNING',
  source: 'GIT',
  lastDeployedAt: '2026-10-02T10:00:00Z',
  createdAt: '2026-10-01T10:00:00Z',
  yaml: 'services:\n  web:\n    image: nginx',
  envKeys: [],
  git,
}
const versions = [
  { id: 'v1', source: 'GIT', commitSha: 'abcdef1234567', createdBy: null, createdAt: '2026-10-01T10:00:00Z', current: true, deployed: true },
]

type Handler = (init?: { method?: string; body?: unknown }) => unknown
function serve(role: string, overrides: Record<string, Handler> = {}, detail: unknown = gitDeployment) {
  apiFetch.mockImplementation((path: string, init?: { method?: string; body?: unknown }) => {
    const key = `${init?.method ?? 'GET'} ${path}`
    if (overrides[key]) {
      const r = overrides[key](init)
      return r instanceof Error ? Promise.reject(r) : Promise.resolve(r)
    }
    if (path === '/auth/me') return Promise.resolve({ username: 'u', role, setupRequired: false })
    if (path === '/git/credentials') {
      return role === 'ADMIN' ? Promise.resolve(credentials) : Promise.reject(new ApiError(403, 'no', 'FORBIDDEN'))
    }
    if (path === `${BASE}/s1`) return Promise.resolve(detail)
    const card = deploymentCardAnswer(path, `${BASE}/s1`, versions)
    if (card) return Promise.resolve(card.body)
    return Promise.reject(new ApiError(404, 'x', 'NOT_FOUND'))
  })
}

function Where() {
  return <p data-testid="where">{useLocation().pathname}</p>
}
const wrap = (ui: React.ReactNode, path = '/') => (
  <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
    <MemoryRouter initialEntries={[path]}>{ui}</MemoryRouter>
  </QueryClientProvider>
)

describe('AddDeploymentDialog, Git source', () => {
  function renderDialog() {
    render(
      wrap(
        <Routes>
          <Route path="/" element={<AddDeploymentDialog hostId="h1" onClose={() => {}} />} />
          <Route path="/environments/h1/deployments/:deploymentId" element={<Where />} />
        </Routes>,
      ),
    )
  }

  async function pickGit() {
    fireEvent.click(await screen.findByRole('radio', { name: 'Git repository' }))
  }
  function fillGit() {
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'shop' } })
    fireEvent.change(screen.getByLabelText('Repository URL'), { target: { value: 'https://github.com/acme/shop.git' } })
    fireEvent.change(screen.getByLabelText('Branch or tag'), { target: { value: 'main' } })
    fireEvent.change(screen.getByLabelText('File path in repository'), { target: { value: 'deploy/compose.yaml' } })
  }

  it('creates a deployment from a repository with a credential and navigates to it', async () => {
    const post = vi.fn(() => gitDeployment)
    serve('ADMIN', { [`POST ${BASE}`]: post })
    renderDialog()
    await pickGit()
    fillGit()
    const select = await screen.findByLabelText('Credential')
    await within(select).findByRole('option', { name: 'GitHub bot' })
    fireEvent.change(select, { target: { value: 'c1' } })
    fireEvent.click(screen.getByRole('button', { name: 'Create deployment' }))
    await waitFor(() => expect(screen.getByTestId('where')).toHaveTextContent('/environments/h1/deployments/s1'))
    expect(post).toHaveBeenCalledWith({
      method: 'POST',
      body: {
        source: 'GIT',
        name: 'shop',
        repoUrl: 'https://github.com/acme/shop.git',
        ref: 'main',
        composePath: 'deploy/compose.yaml',
        credentialId: 'c1',
        env: [],
      },
    })
  })

  it('lets an operator create a public-repo deployment and explains the missing credential list', async () => {
    const post = vi.fn((_init?: { body?: unknown }) => gitDeployment)
    serve('OPERATOR', { [`POST ${BASE}`]: post })
    renderDialog()
    await pickGit()
    fillGit()
    expect(screen.queryByLabelText('Credential')).not.toBeInTheDocument()
    expect(screen.getByText(/Only administrators can pick a credential/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Create deployment' }))
    await waitFor(() => expect(post).toHaveBeenCalled())
    expect(post.mock.calls[0][0]?.body as object).not.toHaveProperty('credentialId')
    expect(apiFetch).not.toHaveBeenCalledWith('/git/credentials')
  })

  it('shows GIT_AUTH_FAILED inline and keeps the form', async () => {
    serve('ADMIN', { [`POST ${BASE}`]: () => new ApiError(422, 'x', 'GIT_AUTH_FAILED') })
    renderDialog()
    await pickGit()
    fillGit()
    fireEvent.click(screen.getByRole('button', { name: 'Create deployment' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('rejected the credentials')
    expect(screen.getByLabelText('Repository URL')).toHaveValue('https://github.com/acme/shop.git')
  })

  it('requires the repository fields before sending', async () => {
    serve('ADMIN')
    renderDialog()
    await pickGit()
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'shop' } })
    fireEvent.click(screen.getByRole('button', { name: 'Create deployment' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Enter the repository URL')
    expect(apiFetch).not.toHaveBeenCalledWith(BASE, expect.anything())
  })
})

describe('DeploymentDetailPage, Git deployment', () => {
  function renderPage() {
    render(
      wrap(
        <Routes>
          <Route path="/environments/:id/deployments/:deploymentId" element={<DeploymentDetailPage />} />
        </Routes>,
        '/environments/h1/deployments/s1',
      ),
    )
  }

  it('shows repository, ref, path and short commit, with a read-only Compose file', async () => {
    serve('OPERATOR')
    renderPage()
    const panel = await screen.findByRole('region', { name: 'Git source' })
    expect(within(panel).getByText('https://github.com/acme/shop.git')).toBeInTheDocument()
    expect(within(panel).getByText('main')).toBeInTheDocument()
    expect(within(panel).getByText('deploy/compose.yaml')).toBeInTheDocument()
    expect(within(panel).getByText('abcdef1')).toBeInTheDocument()
    expect(await screen.findByLabelText('Compose file')).toHaveAttribute('readonly')
  })

  it('does not show a Git panel for file deployments', async () => {
    serve('ADMIN', {}, { ...gitDeployment, source: 'FILE', git: null })
    renderPage()
    await screen.findByRole('heading', { name: 'shop' })
    expect(screen.queryByRole('region', { name: 'Git source' })).not.toBeInTheDocument()
  })

  it('pulls and redeploys, shows the output and the new commit afterwards', async () => {
    let sha = 'abcdef1234567'
    const redeploy = vi.fn(() => {
      sha = '9999999aaaaaa'
      return { runId: 'd-7', versionId: 'v2' }
    })
    serve('OPERATOR', { [`POST ${BASE}/s1/git/redeploy`]: redeploy })
    apiFetch.mockImplementation(((impl) => (path: string, init?: { method?: string }) => {
      if (path === `${BASE}/s1` && !init?.method) return Promise.resolve({ ...gitDeployment, git: { ...git, lastCommitSha: sha } })
      return impl(path, init)
    })(apiFetch.getMockImplementation()!))
    renderPage()
    fireEvent.click(await screen.findByRole('button', { name: 'Pull & redeploy' }))
    expect(await screen.findByLabelText('Deployment output')).toHaveTextContent('d-7')
    expect(await within(screen.getByRole('region', { name: 'Git source' })).findByText('9999999')).toBeInTheDocument()
  })

  it('maps DEPLOYMENT_IN_PROGRESS and git errors on redeploy', async () => {
    serve('OPERATOR', { [`POST ${BASE}/s1/git/redeploy`]: () => new ApiError(409, 'x', 'GIT_UNREACHABLE') })
    renderPage()
    fireEvent.click(await screen.findByRole('button', { name: 'Pull & redeploy' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('could not be reached')
  })

  it('hides pull and auto-update controls from viewers', async () => {
    serve('VIEWER')
    renderPage()
    const panel = await screen.findByRole('region', { name: 'Git source' })
    expect(screen.queryByRole('button', { name: 'Pull & redeploy' })).not.toBeInTheDocument()
    expect(within(panel).getByRole('switch', { name: 'Auto-update' })).toBeDisabled()
  })

  it('saves auto-update with the interval in minutes converted to seconds', async () => {
    const put = vi.fn(() => gitDeployment)
    serve('OPERATOR', { [`PUT ${BASE}/s1/git/auto-update`]: put })
    renderPage()
    const panel = await screen.findByRole('region', { name: 'Git source' })
    fireEvent.click(within(panel).getByRole('switch', { name: 'Auto-update' }))
    fireEvent.change(within(panel).getByLabelText('Check every (minutes)'), { target: { value: '10' } })
    fireEvent.click(within(panel).getByRole('button', { name: 'Save auto-update' }))
    await waitFor(() =>
      expect(put).toHaveBeenCalledWith({ method: 'PUT', body: { enabled: true, intervalSeconds: 600 } }),
    )
    expect(await within(panel).findByRole('status')).toHaveTextContent('Saved')
  })

  it('keeps the saved confirmation when the refreshed detail changes the auto-update settings', async () => {
    let current: typeof gitDeployment = gitDeployment
    serve('OPERATOR', {
      [`GET ${BASE}/s1`]: () => current,
      [`PUT ${BASE}/s1/git/auto-update`]: () => {
        current = { ...current, git: { ...current.git, autoUpdateEnabled: true, autoUpdateIntervalSeconds: 600 } }
        return current
      },
    })
    renderPage()
    const panel = await screen.findByRole('region', { name: 'Git source' })
    fireEvent.click(within(panel).getByRole('switch', { name: 'Auto-update' }))
    fireEvent.change(within(panel).getByLabelText('Check every (minutes)'), { target: { value: '10' } })
    fireEvent.click(within(panel).getByRole('button', { name: 'Save auto-update' }))
    expect(await within(panel).findByRole('status')).toHaveTextContent('Saved')
    // The save invalidates the detail query; wait until the refreshed detail (auto-update on) has arrived.
    await waitFor(() => expect(apiFetch.mock.calls.filter(([path, init]) => path === `${BASE}/s1` && !init).length).toBeGreaterThan(1))
    await waitFor(() => expect(screen.getByRole('switch', { name: 'Auto-update' })).toBeChecked())
    // Query again from the screen: a remounted panel is a new element, so the old reference would be stale.
    expect(within(screen.getByRole('region', { name: 'Git source' })).getByRole('status')).toHaveTextContent('Saved')
  })

  it('rejects an interval below one minute without calling the server', async () => {
    serve('OPERATOR')
    renderPage()
    const panel = await screen.findByRole('region', { name: 'Git source' })
    fireEvent.click(within(panel).getByRole('switch', { name: 'Auto-update' }))
    fireEvent.change(within(panel).getByLabelText('Check every (minutes)'), { target: { value: '0' } })
    fireEvent.click(within(panel).getByRole('button', { name: 'Save auto-update' }))
    expect(await within(panel).findByRole('alert')).toHaveTextContent('at least 1 minute')
    expect(apiFetch).not.toHaveBeenCalledWith(`${BASE}/s1/git/auto-update`, expect.anything())
  })

  it('shows the last poll error and time', async () => {
    serve('ADMIN', {}, {
      ...gitDeployment,
      git: { ...git, autoUpdateEnabled: true, lastPolledAt: '2026-10-03T10:00:00Z', lastPollError: 'GIT_AUTH_FAILED' },
    })
    renderPage()
    const panel = await screen.findByRole('region', { name: 'Git source' })
    expect(within(panel).getByRole('switch', { name: 'Auto-update' })).toBeChecked()
    expect(within(panel).getByRole('alert')).toHaveTextContent('Authentication failed')
  })
})
