import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { Link, MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { apiFetch } from '../../shared/api/client.ts'
import type { TopicHandlers } from '../../shared/realtime/stomp.ts'

vi.mock('../../shared/api/client.ts', async (importActual) => ({
  ...(await importActual<typeof import('../../shared/api/client.ts')>()),
  apiFetch: vi.fn(),
}))
const subscribe = vi.fn()
vi.mock('../../shared/realtime/stomp.ts', () => ({ subscribeToTopic: (...a: unknown[]) => subscribe(...a) }))

import { ChatDockProvider } from './ChatDockProvider.tsx'
import { ChatPage } from './ChatPage.tsx'
import { useChatDock } from './useChatDock.ts'

const session = { id: 's1', title: 'Deploy help', sharedWithAdmin: false, createdAt: '2026-10-23T10:00:00Z', updatedAt: '2026-10-23T10:00:00Z', ownerUsername: null }
const pendingTool = {
  id: 'm2', seq: 3, role: 'TOOL', content: 'Waiting', status: 'PENDING', toolCallId: 'c1', createdAt: '2026-10-23T10:00:00Z',
  toolCalls: [{ id: 'c1', name: 'run_deployment', arguments: {}, token: 't', description: 'Deploy "shop"' }],
}

let handlers: TopicHandlers
let unsubscribe: ReturnType<typeof vi.fn>
let messages: object[]
const frame = (body: object) => act(() => handlers.onMessage(JSON.stringify(body)))

function Status() {
  const dock = useChatDock()
  return (
    <div>
      <p data-testid="unread">{String(dock.unread)}</p>
      <p data-testid="pending">{dock.pendingCount}</p>
      <p data-testid="other-text">{dock.stream.text}</p>
      <Link to="/assistant/s1">back</Link>
    </div>
  )
}

function renderApp() {
  vi.mocked(apiFetch).mockImplementation((p: string, options?: { method?: string }) => {
    const method = options?.method ?? 'GET'
    if (method === 'GET' && p.startsWith('/ai/sessions?')) return Promise.resolve({ items: [session] })
    if (method === 'GET' && p === '/ai/sessions/s1') return Promise.resolve({ session, messages })
    if (method === 'POST' && p === '/ai/sessions/s1/messages') return Promise.resolve({ runId: 'r' })
    if (method === 'DELETE' && p === '/ai/sessions/s1') return Promise.resolve(null)
    return Promise.reject(new Error(`unmocked ${method} ${p}`))
  })
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/assistant/s1']}>
        <ChatDockProvider>
          <Routes>
            <Route path="/assistant/:sessionId" element={<ChatPage role="OPERATOR" />} />
            <Route path="/other" element={<Status />} />
          </Routes>
          <Link to="/other">leave</Link>
        </ChatDockProvider>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

async function startRunAndLeave() {
  renderApp()
  fireEvent.change(await screen.findByLabelText('Message'), { target: { value: 'hello' } })
  fireEvent.click(screen.getByRole('button', { name: 'Send' }))
  await waitFor(() => expect(vi.mocked(apiFetch)).toHaveBeenCalledWith('/ai/sessions/s1/messages', expect.anything()))
  frame({ type: 'TOKEN', text: 'Hi ' })
  fireEvent.click(screen.getByText('leave'))
  await screen.findByTestId('unread')
}

beforeEach(() => {
  messages = []
  unsubscribe = vi.fn()
  subscribe.mockReset()
  subscribe.mockImplementation((_d: string, h: TopicHandlers) => {
    handlers = h
    h.onState('connected')
    return unsubscribe
  })
})
afterEach(cleanup)

describe('ChatDockProvider', () => {
  it('keeps streaming across navigation with a single subscription', async () => {
    await startRunAndLeave()
    frame({ type: 'TOKEN', text: 'there' })
    expect(screen.getByTestId('other-text')).toHaveTextContent('Hi there')
    fireEvent.click(screen.getByText('back'))
    expect(await screen.findByText('Hi there')).toBeInTheDocument()
    expect(subscribe).toHaveBeenCalledTimes(1)
    expect(unsubscribe).not.toHaveBeenCalled()
  })

  it('flags unread when a run finishes elsewhere and clears it when the session is opened', async () => {
    await startRunAndLeave()
    expect(screen.getByTestId('unread')).toHaveTextContent('false')
    messages = [{ id: 'm1', seq: 1, role: 'ASSISTANT', content: 'Done!', toolCalls: null, toolCallId: null, status: null, createdAt: '2026-10-23T10:00:00Z' }]
    frame({ type: 'DONE', outcome: 'COMPLETE' })
    await waitFor(() => expect(screen.getByTestId('unread')).toHaveTextContent('true'))
    fireEvent.click(screen.getByText('back'))
    expect(await screen.findByText('Done!')).toBeInTheDocument()
    fireEvent.click(screen.getByText('leave'))
    expect(await screen.findByTestId('unread')).toHaveTextContent('false')
  })

  it('does not flag unread when the run finishes while the session is open', async () => {
    renderApp()
    await screen.findByLabelText('Message')
    frame({ type: 'TOKEN', text: 'x' })
    frame({ type: 'DONE', outcome: 'COMPLETE' })
    fireEvent.click(screen.getByText('leave'))
    expect(await screen.findByTestId('unread')).toHaveTextContent('false')
  })

  it('exposes the number of pending actions', async () => {
    messages = [pendingTool]
    renderApp()
    await screen.findByText(/Deploy "shop"/)
    fireEvent.click(screen.getByText('leave'))
    expect(await screen.findByTestId('pending')).toHaveTextContent('1')
  })

  it('unsubscribes when the session is deleted', async () => {
    renderApp()
    fireEvent.click(await screen.findByRole('button', { name: /Delete chat/ }))
    fireEvent.click(await screen.findByRole('button', { name: 'Delete' }))
    await waitFor(() => expect(unsubscribe).toHaveBeenCalled())
  })

  it('unsubscribes when the provider unmounts (logout)', async () => {
    const view = renderApp()
    await screen.findByLabelText('Message')
    view.unmount()
    expect(unsubscribe).toHaveBeenCalledTimes(1)
  })
})
