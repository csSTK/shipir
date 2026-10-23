import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../shared/api/client.ts'
import { StacksPage } from './StacksPage.tsx'

const apiFetch = vi.fn()
vi.mock('../../shared/api/client.ts', async (importActual) => ({
  ...(await importActual<typeof import('../../shared/api/client.ts')>()),
  apiFetch: (...args: unknown[]) => apiFetch(...args),
}))

afterEach(() => {
  cleanup()
  apiFetch.mockReset()
})

const stacks = [
  { name: 'shop', containerCount: 3, runningCount: 3, status: 'RUNNING', workingDir: '/srv/shop' },
  { name: 'blog', containerCount: 2, runningCount: 1, status: 'PARTIAL', workingDir: null },
  { name: 'old', containerCount: 1, runningCount: 0, status: 'STOPPED', workingDir: '/srv/old' },
]

function Where() {
  const l = useLocation()
  return <p data-testid="where">{l.pathname + l.search}</p>
}

function renderPage() {
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter initialEntries={['/environments/h1/stacks']}>
        <Routes>
          <Route path="/environments/:id/stacks" element={<StacksPage />} />
          <Route path="/environments/:id/containers" element={<Where />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

function names() {
  return screen
    .getAllByRole('row')
    .slice(1)
    .map((r) => within(r).getAllByRole('cell')[0].textContent)
}

describe('StacksPage', () => {
  it('lists stacks with status, running/total and working directory', async () => {
    apiFetch.mockResolvedValue(stacks)
    renderPage()
    expect(screen.getByRole('status', { name: 'Loading stacks' })).toBeInTheDocument()
    await screen.findByText('/srv/shop')
    expect(apiFetch).toHaveBeenCalledWith('/hosts/h1/stacks')
    expect(names()).toEqual(['blog', 'old', 'shop'])
    const row = screen.getByRole('row', { name: /blog/ })
    expect(within(row).getByText('Partial')).toBeInTheDocument()
    expect(within(row).getByText('1 / 2')).toBeInTheDocument()
  })

  it('searches and sorts by name', async () => {
    apiFetch.mockResolvedValue(stacks)
    renderPage()
    await screen.findByText('/srv/shop')
    fireEvent.click(screen.getByRole('button', { name: 'Name' }))
    expect(names()).toEqual(['shop', 'old', 'blog'])
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'BL' } })
    expect(names()).toEqual(['blog'])
  })

  it('opens the containers page filtered by the clicked stack', async () => {
    apiFetch.mockResolvedValue(stacks)
    renderPage()
    await screen.findByText('/srv/shop')
    fireEvent.click(screen.getByRole('link', { name: 'shop' }))
    expect(screen.getByTestId('where')).toHaveTextContent('/environments/h1/containers?stack=shop')
  })

  it('shows a message for UNREACHABLE', async () => {
    apiFetch.mockRejectedValue(new ApiError(502, 'x', 'UNREACHABLE'))
    renderPage()
    expect(await screen.findByRole('alert')).toHaveTextContent('Could not reach the Docker host.')
  })

  it('shows the empty state', async () => {
    apiFetch.mockResolvedValue([])
    renderPage()
    expect(await screen.findByText('No stacks in this environment.')).toBeInTheDocument()
  })
})
