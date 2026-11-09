import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../../shared/api/client.ts'
import { AddDeploymentDialog } from '../AddDeploymentDialog.tsx'

vi.mock('../../../shared/realtime/stomp.ts', () => ({ subscribeToTopic: () => () => {} }))

const apiFetch = vi.fn()
vi.mock('../../../shared/api/client.ts', async (importActual) => ({
  ...(await importActual<typeof import('../../../shared/api/client.ts')>()),
  apiFetch: (...args: unknown[]) => apiFetch(...args),
}))

afterEach(() => {
  cleanup()
  apiFetch.mockReset()
})

const BASE = '/hosts/h1/managed-deployments'
const LIST = '/portainer-import/stacks'
const created = { id: 's1', name: 'shop', status: 'STOPPED', source: 'FILE', yaml: '', envKeys: [] }
const SIMPLE = 'services:\n  web:\n    image: nginx:1.27\n'
const WITH_BUILD = 'services:\n  web:\n    build: .\n'
/** Generous bound for the fetch -> wizard render after picking a stack; it only costs time when something is wrong. */
const IMPORT_APPLIED_MS = 10_000
const TOKEN = 'ptr_secret_token_123'
const stacks = [
  { id: 7, name: 'shop', type: 'compose', endpointId: 1, supported: true },
  { id: 9, name: 'swarmy', type: 'swarm', endpointId: 1, supported: false, reason: 'Swarm stacks are not supported.' },
]

type Handler = (init?: { body?: unknown }) => unknown

function serve(opts: { list?: Handler; fetch?: Handler; post?: Handler } = {}) {
  apiFetch.mockImplementation((path: string, init?: { method?: string; body?: unknown }) => {
    if (path === '/auth/me') return Promise.resolve({ username: 'u', role: 'ADMIN', setupRequired: false })
    if (path === LIST && init?.method === 'POST') return respond(opts.list ?? (() => stacks), init)
    if (path === `${LIST}/7` && init?.method === 'POST') {
      return respond(opts.fetch ?? (() => ({ name: 'shop', yaml: SIMPLE, env: [{ key: 'PORT', value: '8080' }] })), init)
    }
    if (path === BASE && init?.method === 'POST') return respond(opts.post ?? (() => created), init)
    return Promise.reject(new ApiError(404, 'x', 'NOT_FOUND'))
  })
}
function respond(h: Handler, init?: { body?: unknown }) {
  try {
    return Promise.resolve(h(init))
  } catch (e) {
    return Promise.reject(e)
  }
}

function Where() {
  return <p data-testid="where">{useLocation().pathname}</p>
}

function renderDialog(onClose = () => {}) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const view = render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <Routes>
          <Route path="/" element={<AddDeploymentDialog hostId="h1" onClose={onClose} />} />
          <Route path="/environments/h1/deployments/:deploymentId" element={<Where />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
  return { client, view }
}

async function connect(url = 'https://portainer.example.com', token = TOKEN) {
  fireEvent.click(await screen.findByRole('radio', { name: 'Import from Portainer' }))
  fireEvent.change(screen.getByLabelText('Portainer URL'), { target: { value: url } })
  fireEvent.change(screen.getByLabelText('API token'), { target: { value: token } })
  fireEvent.click(screen.getByRole('button', { name: 'Connect' }))
}

describe('AddDeploymentDialog, Import from Portainer source', () => {
  beforeEach(() => localStorage.clear())

  it('lists the stacks and disables unsupported ones with their reason', async () => {
    serve()
    renderDialog()
    await connect()
    expect(await screen.findByRole('button', { name: 'Import shop' })).toBeEnabled()
    expect(screen.getByRole('button', { name: /swarmy/ })).toBeDisabled()
    expect(screen.getByText('Swarm stacks are not supported.')).toBeInTheDocument()
    expect(apiFetch).toHaveBeenCalledWith(LIST, { method: 'POST', body: { url: 'https://portainer.example.com', apiToken: TOKEN } })
  })

  it('masks the token field', async () => {
    serve()
    renderDialog()
    await connect()
    expect(screen.getByLabelText('API token')).toHaveAttribute('type', 'password')
  })

  it('prefills name, wizard and env rows when a stack is picked', async () => {
    serve()
    renderDialog()
    await connect()
    fireEvent.click(await screen.findByRole('button', { name: 'Import shop' }))
    await waitFor(() => expect(screen.getByRole('radio', { name: 'Form wizard' })).toBeChecked())
    expect(screen.getByLabelText('Name')).toHaveValue('shop')
    expect(screen.getByDisplayValue('nginx:1.27')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('radio', { name: 'YAML' }))
    expect(screen.getByDisplayValue('PORT')).toBeInTheDocument()
    expect(screen.getByDisplayValue('8080')).toBeInTheDocument()
  })

  it('falls back to the YAML editor with a hint and creates through the normal create API', async () => {
    const post = vi.fn((_init?: { body?: unknown }) => created)
    serve({ fetch: () => ({ name: 'shop', yaml: WITH_BUILD, env: [] }), post })
    renderDialog()
    await connect()
    fireEvent.click(await screen.findByRole('button', { name: 'Import shop' }))
    await waitFor(() => expect(screen.getByRole('radio', { name: 'YAML' })).toBeChecked())
    expect(screen.getByRole('status')).toHaveTextContent(/'build' is not supported/)
    fireEvent.click(screen.getByRole('button', { name: 'Create deployment' }))
    await waitFor(() => expect(screen.getByTestId('where')).toHaveTextContent('/environments/h1/deployments/s1'))
    expect(post.mock.calls[0][0]?.body).toEqual({ name: 'shop', yaml: WITH_BUILD, env: [] })
  })

  it('does not overwrite a typed name', async () => {
    serve()
    renderDialog()
    fireEvent.change(await screen.findByLabelText('Name'), { target: { value: 'mine' } })
    await connect()
    fireEvent.click(await screen.findByRole('button', { name: 'Import shop' }))
    // The import is fetch -> state -> wizard render; under a loaded folder-wide run that can take longer than the 1s default.
    // Waiting for the imported image proves the import was applied before the name is checked.
    await screen.findByDisplayValue('nginx:1.27', {}, { timeout: IMPORT_APPLIED_MS })
    expect(screen.getByRole('radio', { name: 'Form wizard' })).toBeChecked()
    expect(screen.getByLabelText('Name')).toHaveValue('mine')
  })

  it.each([
    ['PORTAINER_AUTH_FAILED', 422, /rejected the API token/i],
    ['PORTAINER_UNREACHABLE', 502, /could not be reached/i],
    ['EGRESS_DENIED', 400, /not allowed/i],
    ['INVALID_URL', 400, /valid http/i],
    ['PORTAINER_BAD_RESPONSE', 502, /unexpected response/i],
  ])('shows %s inline when connecting fails', async (code, status, message) => {
    serve({ list: () => { throw new ApiError(status, 'raw', code) } })
    renderDialog()
    await connect()
    expect(await screen.findByRole('alert')).toHaveTextContent(message)
    expect(screen.queryByRole('radio', { name: 'Form wizard' })).not.toBeInTheDocument()
  })

  it.each([
    ['UNSUPPORTED_STACK', 400, /not supported/i],
    ['PORTAINER_STACK_NOT_FOUND', 404, /no longer exists/i],
  ])('shows %s inline when fetching the stack fails', async (code, status, message) => {
    serve({ fetch: () => { throw new ApiError(status, 'raw', code) } })
    renderDialog()
    await connect()
    fireEvent.click(await screen.findByRole('button', { name: 'Import shop' }))
    expect(await screen.findByRole('alert')).toHaveTextContent(message)
    expect(screen.queryByRole('radio', { name: 'Form wizard' })).not.toBeInTheDocument()
  })

  it('never puts the token into storage or the query cache', async () => {
    serve()
    const { client } = renderDialog()
    await connect()
    fireEvent.click(await screen.findByRole('button', { name: 'Import shop' }))
    await waitFor(() => expect(screen.getByRole('radio', { name: 'Form wizard' })).toBeChecked())
    expect(JSON.stringify({ ...localStorage })).not.toContain(TOKEN)
    expect(JSON.stringify({ ...sessionStorage })).not.toContain(TOKEN)
    expect(window.location.href).not.toContain(TOKEN)
    expect(JSON.stringify(client.getQueryCache().getAll().map((q) => q.queryKey))).not.toContain(TOKEN)
    expect(JSON.stringify(client.getMutationCache().getAll().map((m) => m.options.mutationKey ?? null))).not.toContain(TOKEN)
  })

  it('clears the token when the dialog closes', async () => {
    serve()
    const onClose = vi.fn()
    const { view } = renderDialog(onClose)
    await connect()
    await screen.findByRole('button', { name: 'Import shop' })
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }))
    expect(onClose).toHaveBeenCalled()
    view.unmount()
    renderDialog()
    fireEvent.click(await screen.findByRole('radio', { name: 'Import from Portainer' }))
    expect(screen.getByLabelText('API token')).toHaveValue('')
    expect(screen.getByLabelText('Portainer URL')).toHaveValue('')
  })

  it('explains the response size limit and the TLS certificate problem', async () => {
    serve({ list: () => { throw new ApiError(502, 'x', 'PORTAINER_RESPONSE_TOO_LARGE') } })
    renderDialog()
    await connect()
    expect(await screen.findByRole('alert')).toHaveTextContent('1 MiB')
    serve({ list: () => { throw new ApiError(502, 'x', 'PORTAINER_TLS_FAILED') } })
    fireEvent.click(screen.getByRole('button', { name: 'Connect' }))
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent(/certificate/i))
  })

  it('drops URL, token and stack list when the source is switched (the token is not kept around)', async () => {
    serve()
    renderDialog()
    await connect()
    await screen.findByRole('button', { name: 'Import shop' })
    fireEvent.click(screen.getByRole('radio', { name: 'Git repository' }))
    fireEvent.click(screen.getByRole('radio', { name: 'Import from Portainer' }))
    expect(screen.getByLabelText('Portainer URL')).toHaveValue('')
    expect(screen.getByLabelText('API token')).toHaveValue('')
    expect(screen.queryByRole('button', { name: 'Import shop' })).not.toBeInTheDocument()
  })

  it('does not carry the Portainer env into a later file import without an env file', async () => {
    serve()
    renderDialog()
    await connect()
    fireEvent.click(await screen.findByRole('button', { name: 'Import shop' }))
    await waitFor(() => expect(screen.getByRole('radio', { name: 'Form wizard' })).toBeChecked())
    fireEvent.click(screen.getByRole('radio', { name: 'Import file' }))
    fireEvent.change(screen.getByLabelText('Compose file (.yml, .yaml)'), {
      target: { files: [new File([SIMPLE], 'other.yml', { type: 'application/yaml' })] },
    })
    await waitFor(() => expect(screen.getByRole('radio', { name: 'Form wizard' })).toBeChecked())
    fireEvent.click(screen.getByRole('radio', { name: 'YAML' }))
    expect(screen.queryByDisplayValue('PORT')).not.toBeInTheDocument()
    expect(screen.queryByDisplayValue('8080')).not.toBeInTheDocument()
  })

  it('does not run the import form on Enter before a stack was imported', async () => {
    serve()
    renderDialog()
    fireEvent.click(await screen.findByRole('radio', { name: 'Import from Portainer' }))
    fireEvent.submit(screen.getByLabelText('Portainer URL').closest('form')!)
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })
})
