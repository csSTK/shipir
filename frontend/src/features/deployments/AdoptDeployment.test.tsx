import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../shared/api/client.ts'
import { DeploymentsPage } from './DeploymentsPage.tsx'

const apiFetch = vi.fn()
vi.mock('../../shared/api/client.ts', async (importActual) => ({
  ...(await importActual<typeof import('../../shared/api/client.ts')>()),
  apiFetch: (...args: unknown[]) => apiFetch(...args),
}))

afterEach(() => {
  cleanup()
  apiFetch.mockReset()
})

const live = [
  { name: 'blog', containerCount: 1, runningCount: 1, status: 'RUNNING', workingDir: '/srv/blog', managed: false, managedDeploymentId: null },
]

function serve(role: string, adopt: () => Promise<unknown>) {
  apiFetch.mockImplementation((path: string) => {
    if (path === '/auth/me') return Promise.resolve({ username: 'u', role, setupRequired: false })
    if (path === '/hosts/h1/deployments') return Promise.resolve(live)
    if (path === '/hosts/h1/managed-deployments') return Promise.resolve([])
    if (path === '/hosts/h1/deployments/blog/adopt') return adopt()
    return Promise.reject(new ApiError(404, 'x', 'NOT_FOUND'))
  })
}

function Where() {
  return <p data-testid="where">{useLocation().pathname}</p>
}

function renderPage() {
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter initialEntries={['/environments/h1/deployments']}>
        <Routes>
          <Route path="/environments/:id/deployments" element={<DeploymentsPage />} />
          <Route path="/environments/:id/deployments/:deploymentId" element={<Where />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

const adoptCalls = () => apiFetch.mock.calls.filter((c) => c[0] === '/hosts/h1/deployments/blog/adopt')

describe('Adopt deployment', () => {
  it('is not offered to viewers', async () => {
    serve('VIEWER', () => Promise.resolve({}))
    renderPage()
    await screen.findByText('/srv/blog')
    expect(screen.queryByRole('button', { name: /^Adopt/ })).not.toBeInTheDocument()
  })

  it('adopts with the detected files (empty body), warns, and opens the new deployment', async () => {
    serve('OPERATOR', () => Promise.resolve({ id: 'm9', name: 'blog' }))
    renderPage()
    await screen.findByText('/srv/blog')
    fireEvent.click(screen.getByRole('button', { name: 'Adopt blog' }))
    const dialog = await screen.findByRole('dialog', { name: 'Adopt deployment' })
    expect(dialog).toHaveTextContent('Running containers are not restarted')
    expect(dialog).toHaveTextContent('env_file')
    fireEvent.click(within(dialog).getByRole('button', { name: 'Adopt' }))
    await waitFor(() => expect(screen.getByTestId('where')).toHaveTextContent('/environments/h1/deployments/m9'))
    expect(adoptCalls()[0][1]).toEqual({ method: 'POST', body: {} })
  })

  it('asks for the YAML after YAML_REQUIRED and sends it with the variables', async () => {
    let n = 0
    serve('ADMIN', () => (n++ === 0 ? Promise.reject(new ApiError(400, 'no file', 'YAML_REQUIRED')) : Promise.resolve({ id: 'm9' })))
    renderPage()
    await screen.findByText('/srv/blog')
    fireEvent.click(screen.getByRole('button', { name: 'Adopt blog' }))
    const dialog = await screen.findByRole('dialog', { name: 'Adopt deployment' })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Adopt' }))
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Paste it below')
    fireEvent.change(await within(dialog).findByRole('textbox', { name: 'Compose file' }), { target: { value: 'services: {}' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Adopt' }))
    await waitFor(() => expect(screen.getByTestId('where')).toHaveTextContent('/m9'))
    expect(adoptCalls()[1][1]).toEqual({ method: 'POST', body: { yaml: 'services: {}', env: [] } })
  })

  it('shows a server error such as a taken name inside the dialog', async () => {
    serve('OPERATOR', () => Promise.reject(new ApiError(409, 'taken', 'DEPLOYMENT_NAME_TAKEN')))
    renderPage()
    await screen.findByText('/srv/blog')
    fireEvent.click(screen.getByRole('button', { name: 'Adopt blog' }))
    const dialog = await screen.findByRole('dialog', { name: 'Adopt deployment' })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Adopt' }))
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('already exists')
  })

  async function open() {
    renderPage()
    await screen.findByText('/srv/blog')
    fireEvent.click(screen.getByRole('button', { name: 'Adopt blog' }))
    const dialog = await screen.findByRole('dialog', { name: 'Adopt deployment' })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Adopt' }))
    return dialog
  }

  it('shows the paste editor after PATH_NOT_ALLOWED', async () => {
    serve('OPERATOR', () => Promise.reject(new ApiError(403, 'no', 'PATH_NOT_ALLOWED')))
    const dialog = await open()
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Paste the YAML')
    expect(await within(dialog).findByRole('textbox', { name: 'Compose file' })).toBeInTheDocument()
  })

  it('names the relative paths when adoption is refused and offers the paste editor', async () => {
    serve('OPERATOR', () =>
      Promise.reject(new ApiError(422, 'refused', 'RELATIVE_PATHS_NOT_SUPPORTED', undefined, ['web bind mount: ./html', 'web env_file: .env'])),
    )
    const dialog = await open()
    const alert = await within(dialog).findByRole('alert')
    expect(alert).toHaveTextContent('web bind mount: ./html')
    expect(alert).toHaveTextContent('web env_file: .env')
    expect(await within(dialog).findByRole('textbox', { name: 'Compose file' })).toBeInTheDocument()
  })

  it('shows the editor and message when the detected file is invalid', async () => {
    serve('OPERATOR', () => Promise.reject(new ApiError(400, 'bad', 'INVALID_COMPOSE', undefined, ['services.web: bad'])))
    const dialog = await open()
    expect(await within(dialog).findByText(/Paste a corrected file/)).toBeInTheDocument()
    expect(await within(dialog).findByRole('textbox', { name: 'Compose file' })).toBeInTheDocument()
  })

  it('shows INVALID_COMPOSE details for pasted YAML', async () => {
    let n = 0
    serve('OPERATOR', () =>
      n++ === 0 ? Promise.reject(new ApiError(400, 'x', 'YAML_REQUIRED')) : Promise.reject(new ApiError(400, 'bad', 'INVALID_COMPOSE', undefined, ['services.web: bad'])),
    )
    const dialog = await open()
    fireEvent.change(await within(dialog).findByRole('textbox', { name: 'Compose file' }), { target: { value: 'x: 1' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Adopt' }))
    await waitFor(() => expect(adoptCalls()).toHaveLength(2))
    expect(await within(dialog).findByText(/services\.web: bad/)).toBeInTheDocument()
  })
})
