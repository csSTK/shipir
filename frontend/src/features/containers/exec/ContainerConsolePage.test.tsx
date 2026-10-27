import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { TopicHandlers } from '../../../shared/realtime/stomp.ts'
import { ApiError } from '../../../shared/api/client.ts'

const term = vi.hoisted(() => ({
  cols: 100,
  rows: 30,
  write: vi.fn(),
  reset: vi.fn(),
  focus: vi.fn(),
  dispose: vi.fn(),
  loadAddon: vi.fn(),
  open: vi.fn(),
  dataCb: null as null | ((d: string) => void),
  resizeCb: null as null | ((s: { cols: number; rows: number }) => void),
}))
vi.mock('@xterm/xterm', () => ({
  Terminal: class {
    get cols() { return term.cols }
    get rows() { return term.rows }
    write = term.write
    reset = term.reset
    focus = term.focus
    dispose = term.dispose
    loadAddon = term.loadAddon
    open = term.open
    onData(cb: (d: string) => void) { term.dataCb = cb; return { dispose: vi.fn() } }
    onResize(cb: (s: { cols: number; rows: number }) => void) { term.resizeCb = cb; return { dispose: vi.fn() } }
  },
}))
vi.mock('@xterm/addon-fit', () => ({ FitAddon: class { fit = vi.fn() } }))
vi.mock('@xterm/xterm/css/xterm.css', () => ({}))

const open = vi.fn()
const publish = vi.fn()
const close = vi.fn()
vi.mock('../../../shared/realtime/stomp.ts', () => ({ openTopicChannel: (...a: unknown[]) => open(...a) }))
const apiFetch = vi.fn()
vi.mock('../../../shared/api/client.ts', async (orig) => ({ ...(await orig<object>()), apiFetch: (...a: unknown[]) => apiFetch(...a) }))
vi.mock('../api.ts', () => ({ useContainers: () => ({ data: [{ id: 'c1', name: 'web' }] }) }))

import { ContainerConsolePage } from './ContainerConsolePage.tsx'

const session = { sessionId: 's1', output: '/topic/exec/s1', input: '/app/exec/s1/input', resize: '/app/exec/s1/resize' }
let handlers: TopicHandlers
const frame = (f: object) => act(() => handlers.onMessage(JSON.stringify(f)))

function renderPage() {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { mutations: { retry: false } } })}>
      <MemoryRouter initialEntries={['/environments/h1/containers/c1/console']}>
        <Routes>
          <Route path="/environments/:id/containers/:cid/console" element={<ContainerConsolePage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

async function connect() {
  fireEvent.click(screen.getByRole('button', { name: 'Connect' }))
  await waitFor(() => expect(open).toHaveBeenCalled())
  act(() => handlers.onState('connected'))
}

beforeEach(() => {
  vi.stubGlobal('ResizeObserver', class { observe() {} disconnect() {} unobserve() {} })
  apiFetch.mockResolvedValue(session)
  open.mockImplementation((_d: string, h: TopicHandlers) => {
    handlers = h
    return { publish, close }
  })
})
afterEach(() => {
  cleanup()
  vi.clearAllMocks()
  vi.unstubAllGlobals()
})

describe('ContainerConsolePage', () => {
  it('starts the exec with shell and terminal size, then subscribes to the output topic', async () => {
    renderPage()
    fireEvent.change(screen.getByLabelText('Shell'), { target: { value: '/bin/bash' } })
    await connect()
    expect(apiFetch).toHaveBeenCalledWith('/hosts/h1/containers/c1/exec', { method: 'POST', body: { shell: '/bin/bash', cols: 100, rows: 30 } })
    expect(open).toHaveBeenCalledWith('/topic/exec/s1', expect.anything())
    expect(screen.getByRole('status')).toHaveTextContent('Connected')
  })

  it('sends typed input as bytes to the input destination', async () => {
    renderPage()
    await connect()
    term.dataCb?.('ls\r')
    expect(publish).toHaveBeenCalledWith('/app/exec/s1/input', new TextEncoder().encode('ls\r'))
  })

  it('renders output frames once (deduplicated by seq) and sends the new size on resize', async () => {
    renderPage()
    await connect()
    const b64 = btoa('hello')
    frame({ seq: 1, type: 'OUTPUT', data: b64 })
    frame({ seq: 1, type: 'OUTPUT', data: b64 })
    expect(term.write).toHaveBeenCalledTimes(1)
    expect(new TextDecoder().decode(term.write.mock.calls[0][0])).toBe('hello')
    term.resizeCb?.({ cols: 120, rows: 40 })
    expect(publish).toHaveBeenCalledWith('/app/exec/s1/resize', JSON.stringify({ cols: 120, rows: 40 }))
  })

  it('shows the ended state when the server ends the session and allows connecting again', async () => {
    renderPage()
    await connect()
    frame({ seq: 1, type: 'STATUS', status: 'ENDED' })
    expect(screen.getByRole('status')).toHaveTextContent('Disconnected')
    expect(screen.getByText('The session ended.')).toBeInTheDocument()
    expect(close).toHaveBeenCalled()
    expect(screen.getByRole('button', { name: 'Connect' })).toBeEnabled()
  })

  it('disconnect closes the channel', async () => {
    renderPage()
    await connect()
    fireEvent.click(screen.getByRole('button', { name: 'Disconnect' }))
    expect(close).toHaveBeenCalled()
    expect(screen.getByRole('button', { name: 'Connect' })).toBeInTheDocument()
  })

  it('shows a lost connection as an error and closes the session', async () => {
    renderPage()
    await connect()
    act(() => handlers.onState('reconnecting'))
    expect(screen.getByRole('alert')).toHaveTextContent('Connection lost')
    expect(close).toHaveBeenCalled()
  })

  it('maps a start error to a message and stays unconnected', async () => {
    apiFetch.mockRejectedValue(new ApiError(409, 'x', 'INVALID_CONTAINER_STATE'))
    renderPage()
    fireEvent.click(screen.getByRole('button', { name: 'Connect' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('not running')
    expect(open).not.toHaveBeenCalled()
    expect(screen.getByRole('status')).toHaveTextContent('Not connected')
  })

  it('explains a 403 as missing permission for this container', async () => {
    apiFetch.mockRejectedValue(new ApiError(403, 'x', 'FORBIDDEN'))
    renderPage()
    fireEvent.click(screen.getByRole('button', { name: 'Connect' }))
    expect(await screen.findByRole('alert')).toHaveTextContent("You don't have permission to open a console for this container.")
    expect(open).not.toHaveBeenCalled()
  })
})
