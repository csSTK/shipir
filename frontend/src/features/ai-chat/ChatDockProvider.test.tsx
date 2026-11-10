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
let byTopic: Record<string, { handlers: TopicHandlers; unsubscribe: ReturnType<typeof vi.fn> }>
let unsubscribe: ReturnType<typeof vi.fn>
let messages: object[]
let serverRunning: boolean
let sessionGate: Promise<void> | null
let pendingTotal: number
const userMsg = { id: 'm0', seq: 1, role: 'USER', content: 'hello', toolCalls: null, toolCallId: null, status: null, createdAt: '2026-10-23T10:00:00Z' }
const answerMsg = { id: 'm1', seq: 2, role: 'ASSISTANT', content: 'Done!', toolCalls: null, toolCallId: null, status: null, createdAt: '2026-10-23T10:00:00Z' }
const frame = (body: object) => act(() => handlers.onMessage(JSON.stringify(body)))
const frameOn = (topic: string, body: object) => act(() => byTopic[topic].handlers.onMessage(JSON.stringify(body)))

function Status() {
  const dock = useChatDock()
  return (
    <div>
      <p data-testid="unread">{String(dock.unread)}</p>
      <p data-testid="pending">{dock.pendingCount}</p>
      <p data-testid="other-text">{dock.stream.text}</p>
      <p data-testid="elsewhere">{String(dock.runningElsewhere)}</p>
      <button type="button" onClick={() => dock.open('s2')}>switch</button>
      <button type="button" onClick={() => dock.open('s1')}>open s1</button>
      <p data-testid="running">{String(dock.stream.running)}</p>
      <Link to="/assistant/s1">back</Link>
    </div>
  )
}

function renderApp(initialPath = '/assistant/s1') {
  vi.mocked(apiFetch).mockImplementation((p: string, options?: { method?: string }) => {
    const method = options?.method ?? 'GET'
    if (method === 'GET' && p.startsWith('/ai/sessions?')) return Promise.resolve({ items: [session] })
    if (method === 'GET' && p === '/ai/sessions/pending-count') return Promise.resolve({ count: pendingTotal })
    if (method === 'GET' && p === '/ai/sessions/s2') return Promise.resolve({ session: { ...session, id: 's2' }, messages: [], running: false })
    if (method === 'GET' && p === '/ai/sessions/s1') {
      const detail = { session, messages, running: serverRunning }
      const gate = sessionGate
      sessionGate = null // only the first answer is held back
      return gate ? gate.then(() => detail) : Promise.resolve(detail)
    }
    if (method === 'POST' && p === '/ai/sessions/s1/messages') return Promise.resolve({ runId: 'r' })
    if (method === 'DELETE' && p === '/ai/sessions/s1') return Promise.resolve(null)
    return Promise.reject(new Error(`unmocked ${method} ${p}`))
  })
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[initialPath]}>
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
  serverRunning = false
  sessionGate = null
  pendingTotal = 0
  byTopic = {}
  unsubscribe = vi.fn()
  subscribe.mockReset()
  subscribe.mockImplementation((destination: string, h: TopicHandlers) => {
    handlers = h
    h.onState('connected')
    const off = destination === '/topic/chat/s1' ? unsubscribe : vi.fn()
    byTopic[destination] = { handlers: h, unsubscribe: off }
    return off
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
    pendingTotal = 1
    renderApp()
    await screen.findByText(/Deploy "shop"/)
    fireEvent.click(screen.getByText('leave'))
    expect(await screen.findByTestId('pending')).toHaveTextContent('1')
  })

  it('counts pending actions of stored sessions right after a reload, before any chat is opened', async () => {
    pendingTotal = 2
    renderApp()
    await screen.findByLabelText('Message')
    fireEvent.click(screen.getByText('leave'))
    expect(await screen.findByTestId('pending')).toHaveTextContent('2')
  })

  it('keeps following a run after switching to another chat and flags it unread when it finishes there', async () => {
    await startRunAndLeave()
    messages = [userMsg]
    serverRunning = true
    fireEvent.click(screen.getByText('switch'))
    await waitFor(() => expect(subscribe).toHaveBeenCalledWith('/topic/chat/s2', expect.anything()))
    // The active subscription ends with the switch; a background one on the same chat takes over.
    expect(subscribe.mock.calls.filter(([d]) => d === '/topic/chat/s1')).toHaveLength(2)
    expect(screen.getByTestId('elsewhere')).toHaveTextContent('true')
    expect(screen.getByTestId('unread')).toHaveTextContent('false')

    pendingTotal = 1
    frameOn('/topic/chat/s1', { type: 'DONE', outcome: 'COMPLETE' })
    await waitFor(() => expect(screen.getByTestId('unread')).toHaveTextContent('true'))
    await waitFor(() => expect(screen.getByTestId('pending')).toHaveTextContent('1'))
    expect(screen.getByTestId('elsewhere')).toHaveTextContent('false')
    await waitFor(() => expect(unsubscribe).toHaveBeenCalledTimes(2))
  })

  it('drops a background run whose DONE frame was missed during the switch', async () => {
    await startRunAndLeave()
    // The run ended between leaving the active subscription and the background one; no frame will ever replay.
    messages = [userMsg, answerMsg]
    fireEvent.click(screen.getByText('switch'))
    await waitFor(() => expect(screen.getByTestId('unread')).toHaveTextContent('true'))
    expect(screen.getByTestId('elsewhere')).toHaveTextContent('false')
    await waitFor(() => expect(unsubscribe).toHaveBeenCalledTimes(2))
  })

  it('keeps following a background run whose last message is a confirmed tool result', async () => {
    await startRunAndLeave()
    // Resolving an action starts a new run, so a CONFIRMED tool message does not mean the chat is idle.
    messages = [userMsg, { ...pendingTool, status: 'CONFIRMED' }]
    serverRunning = true
    fireEvent.click(screen.getByText('switch'))
    await waitFor(() => expect(subscribe).toHaveBeenCalledWith('/topic/chat/s2', expect.anything()))
    await waitFor(() => expect(vi.mocked(apiFetch).mock.calls.filter(([p]) => p === '/ai/sessions/s1').length).toBeGreaterThan(1))
    await act(() => Promise.resolve())
    expect(screen.getByTestId('elsewhere')).toHaveTextContent('true')
    expect(screen.getByTestId('unread')).toHaveTextContent('false')
  })

  it('stops following a background run when the websocket is lost', async () => {
    await startRunAndLeave()
    messages = [userMsg]
    serverRunning = true
    fireEvent.click(screen.getByText('switch'))
    await waitFor(() => expect(screen.getByTestId('elsewhere')).toHaveTextContent('true'))
    act(() => byTopic['/topic/chat/s1'].handlers.onState('reconnecting'))
    await waitFor(() => expect(screen.getByTestId('elsewhere')).toHaveTextContent('false'))
  })

  it('follows a run that is already going when the chat is opened after a reload', async () => {
    messages = [userMsg]
    serverRunning = true
    renderApp()
    await screen.findByLabelText('Message')
    expect(await screen.findByText('Thinking…')).toBeInTheDocument()
    frame({ type: 'DONE', outcome: 'COMPLETE' })
    await waitFor(() => expect(screen.queryByText('Thinking…')).not.toBeInTheDocument())
  })

  it('does not stay running when the DONE frame arrives between the session fetch and following the run', async () => {
    // No chat view observes the session query here, so the DONE refresh cannot supersede the slow first response.
    serverRunning = true
    let release!: () => void
    sessionGate = new Promise<void>((resolve) => {
      release = resolve
    })
    renderApp('/other')
    fireEvent.click(await screen.findByText('open s1'))
    await waitFor(() => expect(byTopic['/topic/chat/s1']).toBeDefined())
    // The server said "running"; the run then ended and its DONE frame overtook that response.
    serverRunning = false
    frame({ type: 'DONE', outcome: 'COMPLETE' })
    await new Promise((resolve) => setTimeout(resolve, 50))
    release()
    await new Promise((resolve) => setTimeout(resolve, 50))
    expect(screen.getByTestId('running')).toHaveTextContent('false')
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
