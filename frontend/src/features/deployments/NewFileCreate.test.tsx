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
const YAML = 'services:\n  web:\n    image: nginx\n'

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

describe('AddDeploymentDialog, New file source', () => {
  it('has exactly one field labelled Compose file, so the editor is the first match', async () => {
    apiFetch.mockResolvedValue({ username: 'u', role: 'ADMIN', setupRequired: false })
    renderDialog()
    await screen.findByLabelText('Name')
    expect(await screen.findAllByLabelText(/Compose file/)).toHaveLength(1)
  })

  it('creates a deployment from the typed Compose file', async () => {
    const post = vi.fn((_init?: { body?: unknown }) => ({ id: 's1', name: 'shop' }))
    apiFetch.mockImplementation((path: string, init?: { method?: string; body?: unknown }) => {
      if (path === '/auth/me') return Promise.resolve({ username: 'u', role: 'ADMIN', setupRequired: false })
      if (path === BASE && init?.method === 'POST') return Promise.resolve(post(init))
      return Promise.reject(new ApiError(404, 'x', 'NOT_FOUND'))
    })
    renderDialog()
    fireEvent.change(await screen.findByLabelText('Name'), { target: { value: 'shop' } })
    fireEvent.change((await screen.findAllByLabelText(/Compose file/))[0], { target: { value: YAML } })
    fireEvent.click(screen.getByRole('button', { name: 'Create deployment' }))
    await waitFor(() => expect(screen.getByTestId('where')).toHaveTextContent('/environments/h1/deployments/s1'))
    expect(post.mock.calls[0][0]?.body).toEqual({ name: 'shop', yaml: YAML, env: [] })
  })
})
