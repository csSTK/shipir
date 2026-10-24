import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { apiFetch } from '../../shared/api/client.ts'
import { CommandPalette } from './CommandPalette.tsx'

vi.mock('../../shared/api/client.ts', async (importActual) => ({
  ...(await importActual<typeof import('../../shared/api/client.ts')>()),
  apiFetch: vi.fn(),
}))

const RESULTS = {
  truncated: false,
  results: [
    { type: 'HOST', id: 'h1', name: 'prod', hostId: 'h1', hostName: 'prod', managed: false, detail: null },
    { type: 'DEPLOYMENT', id: 'd1', name: 'web', hostId: 'h1', hostName: 'prod', managed: true, detail: 'RUNNING' },
    { type: 'DEPLOYMENT', id: null, name: 'legacy', hostId: 'h1', hostName: 'prod', managed: false, detail: null },
    { type: 'CONTAINER', id: 'c1', name: 'web-1', hostId: 'h1', hostName: 'prod', managed: false, detail: 'nginx:1' },
  ],
}

function Where() {
  const l = useLocation()
  return <p data-testid="where">{l.pathname + l.search}</p>
}

function setup(role: 'ADMIN' | 'VIEWER' = 'VIEWER', onClose = vi.fn()) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <CommandPalette role={role} open onClose={onClose} />
        <Where />
      </MemoryRouter>
    </QueryClientProvider>,
  )
  return { onClose, input: screen.getByRole('combobox') }
}

beforeEach(() => {
  vi.mocked(apiFetch).mockReset()
  vi.mocked(apiFetch).mockResolvedValue(RESULTS)
})
afterEach(cleanup)

describe('CommandPalette', () => {
  it('renders nothing when closed', () => {
    render(
      <QueryClientProvider client={new QueryClient()}>
        <MemoryRouter>
          <CommandPalette role="VIEWER" open={false} onClose={vi.fn()} />
        </MemoryRouter>
      </QueryClientProvider>,
    )
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('has combobox and listbox semantics and lists navigation commands', () => {
    const { input } = setup()
    expect(screen.getByRole('dialog')).toHaveAttribute('aria-modal', 'true')
    expect(input).toHaveAttribute('aria-expanded', 'true')
    expect(input).toHaveAttribute('aria-controls', screen.getByRole('listbox').id)
    expect(screen.getByRole('option', { name: /Environments/ })).toBeInTheDocument()
    expect(screen.getByRole('option', { name: /Assistant/ })).toBeInTheDocument()
    expect(screen.queryByRole('option', { name: /Audit log/ })).not.toBeInTheDocument()
    expect(apiFetch).not.toHaveBeenCalled()
  })

  it('shows settings commands to ADMIN only', () => {
    setup('ADMIN')
    expect(screen.getByRole('option', { name: /Audit log/ })).toBeInTheDocument()
  })

  it('does not call the server below two characters and filters commands locally', async () => {
    const { input } = setup()
    fireEvent.change(input, { target: { value: 'n' } })
    await new Promise((r) => setTimeout(r, 300))
    expect(apiFetch).not.toHaveBeenCalled()
    expect(screen.getByRole('option', { name: /Notifications/ })).toBeInTheDocument()
  })

  it('debounces and searches, grouping results', async () => {
    const { input } = setup()
    fireEvent.change(input, { target: { value: 'w' } })
    fireEvent.change(input, { target: { value: 'we' } })
    fireEvent.change(input, { target: { value: 'web' } })
    expect(await screen.findByRole('option', { name: /web-1/ })).toBeInTheDocument()
    expect(apiFetch).toHaveBeenCalledTimes(1)
    expect(apiFetch).toHaveBeenCalledWith('/search?q=web')
    for (const heading of ['Environments', 'Deployments', 'Containers']) {
      expect(screen.getByRole('group', { name: heading })).toBeInTheDocument()
    }
  })

  it('navigates with arrows and Enter to the right route per type', async () => {
    const cases: [string, string][] = [
      ['prod', '/environments/h1'],
      ['web', '/environments/h1/deployments/d1'],
      ['legacy', '/environments/h1/containers?deployment=legacy'],
      ['web-1', '/environments/h1/containers/c1'],
    ]
    for (const [name, path] of cases) {
      const { input, onClose } = setup()
      fireEvent.change(input, { target: { value: 'zz' } })
      const option = await screen.findByRole('option', { name: new RegExp(`^${name}( |$)`) })
      // Move the active option by keyboard until it is the wanted one.
      for (let i = 0; i < 10 && option.getAttribute('aria-selected') !== 'true'; i++) {
        fireEvent.keyDown(input, { key: 'ArrowDown' })
      }
      expect(input).toHaveAttribute('aria-activedescendant', option.id)
      fireEvent.keyDown(input, { key: 'Enter' })
      expect(screen.getByTestId('where')).toHaveTextContent(path)
      expect(onClose).toHaveBeenCalled()
      cleanup()
    }
  })

  it('ArrowUp wraps and a command navigates', () => {
    const { input, onClose } = setup()
    fireEvent.keyDown(input, { key: 'ArrowUp' })
    const options = screen.getAllByRole('option')
    expect(options[options.length - 1]).toHaveAttribute('aria-selected', 'true')
    fireEvent.click(screen.getByRole('option', { name: /Notifications/ }))
    expect(screen.getByTestId('where')).toHaveTextContent('/notifications')
    expect(onClose).toHaveBeenCalled()
  })

  it('Escape closes', () => {
    const { input, onClose } = setup()
    fireEvent.keyDown(input, { key: 'Escape' })
    expect(onClose).toHaveBeenCalled()
  })

  it('shows empty and error states', async () => {
    vi.mocked(apiFetch).mockResolvedValueOnce({ results: [], truncated: false })
    const { input } = setup()
    fireEvent.change(input, { target: { value: 'nothing-here' } })
    expect(await screen.findByText(/No results/)).toBeInTheDocument()
    cleanup()
    vi.mocked(apiFetch).mockRejectedValueOnce(new Error('boom'))
    const second = setup()
    fireEvent.change(second.input, { target: { value: 'broken' } })
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent(/Search failed/))
  })

  it('notes truncated results', async () => {
    vi.mocked(apiFetch).mockResolvedValueOnce({ ...RESULTS, truncated: true })
    const { input } = setup()
    fireEvent.change(input, { target: { value: 'web' } })
    expect(await screen.findByText(/Showing the first/)).toBeInTheDocument()
  })

  it('keeps focus inside on Tab', () => {
    const { input } = setup()
    input.focus()
    fireEvent.keyDown(input, { key: 'Tab' })
    expect(screen.getByRole('dialog').contains(document.activeElement)).toBe(true)
  })
})
