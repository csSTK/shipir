import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../shared/api/client.ts'
import { AddDeploymentDialog } from './AddDeploymentDialog.tsx'

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
const created = { id: 's1', name: 'shop', status: 'STOPPED', source: 'FILE', yaml: '', envKeys: [] }
const SIMPLE = 'services:\n  web:\n    image: nginx:1.27\n'
const WITH_BUILD = 'services:\n  web:\n    build: .\n'

function serve(post: (init?: { body?: unknown }) => unknown = () => created) {
  apiFetch.mockImplementation((path: string, init?: { method?: string; body?: unknown }) => {
    if (path === '/auth/me') return Promise.resolve({ username: 'u', role: 'ADMIN', setupRequired: false })
    if (path === BASE && init?.method === 'POST') return Promise.resolve(post(init))
    return Promise.reject(new ApiError(404, 'x', 'NOT_FOUND'))
  })
}

function Where() {
  return <p data-testid="where">{useLocation().pathname}</p>
}

function renderDialog() {
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter>
        <Routes>
          <Route path="/" element={<AddDeploymentDialog hostId="h1" onClose={() => {}} />} />
          <Route path="/environments/h1/deployments/:deploymentId" element={<Where />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

async function chooseImport() {
  fireEvent.click(await screen.findByRole('radio', { name: 'Import file' }))
}
const pick = (label: string, file: File) => fireEvent.change(screen.getByLabelText(label), { target: { files: [file] } })
const composeFile = (text: string, name = 'shop.yml') => new File([text], name, { type: 'application/yaml' })
const envFile = (text: string, name = 'stack.env') => new File([text], name, { type: 'text/plain' })

describe('AddDeploymentDialog, Import file source', () => {
  it('gives the two picker buttons different accessible names', async () => {
    serve()
    renderDialog()
    await chooseImport()
    expect(screen.getByRole('button', { name: 'Choose file, Compose file (.yml, .yaml)' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Choose file, Environment file (optional)' })).toBeInTheDocument()
  })

  it('prefills name, wizard and env rows when the wizard can represent the file', async () => {
    serve()
    renderDialog()
    await chooseImport()
    pick('Compose file (.yml, .yaml)', composeFile(SIMPLE))
    pick('Environment file (optional)', envFile('PORT=8080\nDEBUG=1\n'))
    await waitFor(() => expect(screen.getByRole('radio', { name: 'Form wizard' })).toBeChecked())
    expect(screen.getByLabelText('Name')).toHaveValue('shop')
    expect(screen.getByDisplayValue('nginx:1.27')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('radio', { name: 'YAML' }))
    expect(screen.getByDisplayValue('PORT')).toBeInTheDocument()
    expect(screen.getByDisplayValue('8080')).toBeInTheDocument()
    expect(screen.queryByText(/form wizard cannot show this file/i)).not.toBeInTheDocument()
  })

  it('falls back to the YAML editor with a hint and the reason when the wizard cannot represent the file', async () => {
    serve()
    renderDialog()
    await chooseImport()
    pick('Compose file (.yml, .yaml)', composeFile(WITH_BUILD))
    await waitFor(() => expect(screen.getByRole('radio', { name: 'YAML' })).toBeChecked())
    expect(screen.getByLabelText('Compose file', { selector: 'textarea' })).toHaveValue(WITH_BUILD)
    expect(screen.getByRole('status')).toHaveTextContent(/form wizard cannot show this file/i)
    expect(screen.getByRole('status')).toHaveTextContent(/'build' is not supported/)
  })

  it('creates the deployment through the existing create API with the imported YAML and env', async () => {
    const post = vi.fn((_init?: { body?: unknown }) => created)
    serve(post)
    renderDialog()
    await chooseImport()
    pick('Compose file (.yml, .yaml)', composeFile(WITH_BUILD))
    pick('Environment file (optional)', envFile('TOKEN="abc def"\n'))
    await waitFor(() => expect(screen.getByRole('radio', { name: 'YAML' })).toBeChecked())
    fireEvent.click(screen.getByRole('button', { name: 'Create deployment' }))
    await waitFor(() => expect(screen.getByTestId('where')).toHaveTextContent('/environments/h1/deployments/s1'))
    expect(post.mock.calls[0][0]?.body).toEqual({ name: 'shop', yaml: WITH_BUILD, env: [{ key: 'TOKEN', value: 'abc def' }] })
  })

  it('keeps an already typed name instead of overwriting it with the file name', async () => {
    serve()
    renderDialog()
    fireEvent.change(await screen.findByLabelText('Name'), { target: { value: 'mine' } })
    await chooseImport()
    pick('Compose file (.yml, .yaml)', composeFile(SIMPLE))
    await waitFor(() => expect(screen.getByRole('radio', { name: 'Form wizard' })).toBeChecked())
    expect(screen.getByLabelText('Name')).toHaveValue('mine')
  })

  it('shows an inline error for an oversize compose file and imports nothing', async () => {
    serve()
    renderDialog()
    await chooseImport()
    pick('Compose file (.yml, .yaml)', composeFile('#'.repeat(512 * 1024 + 1)))
    expect(await screen.findByRole('alert')).toHaveTextContent(/too large/i)
    expect(screen.queryByRole('radio', { name: 'Form wizard' })).not.toBeInTheDocument()
    expect(apiFetch).not.toHaveBeenCalledWith(BASE, expect.anything())
  })

  it('rejects a compose file that is not .yml or .yaml', async () => {
    serve()
    renderDialog()
    await chooseImport()
    pick('Compose file (.yml, .yaml)', composeFile(SIMPLE, 'shop.txt'))
    expect(await screen.findByRole('alert')).toHaveTextContent(/\.yml or \.yaml/)
    expect(screen.queryByRole('radio', { name: 'Form wizard' })).not.toBeInTheDocument()
  })

  it.each([
    ['binary', 'A=1\u0000\u0001', /not a text file/i],
    ['unparseable', 'A=1\nthis is not an env line\n', /line 2/i],
  ])('shows an inline error for a %s env file and keeps the previous env rows', async (_label, text, message) => {
    serve()
    renderDialog()
    await chooseImport()
    pick('Compose file (.yml, .yaml)', composeFile(WITH_BUILD))
    pick('Environment file (optional)', envFile('KEEP=1\n'))
    await waitFor(() => expect(screen.getByDisplayValue('KEEP')).toBeInTheDocument())
    pick('Environment file (optional)', envFile(text))
    expect(await screen.findByRole('alert')).toHaveTextContent(message)
    expect(screen.getByDisplayValue('KEEP')).toBeInTheDocument()
  })

  it('shows an inline error for an oversize env file', async () => {
    serve()
    renderDialog()
    await chooseImport()
    pick('Environment file (optional)', envFile('A=' + 'x'.repeat(512 * 1024)))
    expect(await screen.findByRole('alert')).toHaveTextContent(/too large/i)
  })
})
