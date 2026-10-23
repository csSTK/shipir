import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, apiFetch } from '../../shared/api/client.ts'
import type { TopicHandlers } from '../../shared/realtime/stomp.ts'

vi.mock('../../shared/api/client.ts', async (importActual) => ({
  ...(await importActual<typeof import('../../shared/api/client.ts')>()),
  apiFetch: vi.fn(),
}))
const subscribe = vi.fn()
vi.mock('../../shared/realtime/stomp.ts', () => ({ subscribeToTopic: (...a: unknown[]) => subscribe(...a) }))

import { ChatPage } from './ChatPage.tsx'

const apiFetchMock = vi.mocked(apiFetch)
let handlers: TopicHandlers
const frame = (body: object) => act(() => handlers.onMessage(JSON.stringify(body)))

const RUN = '11111111-1111-1111-1111-111111111111'
const session = { id: 's1', title: 'Deploy help', sharedWithAdmin: false, createdAt: '2026-10-23T10:00:00Z', updatedAt: '2026-10-23T10:00:00Z', ownerUsername: null }
const other = { ...session, id: 's2', title: 'Old chat' }

interface Env {
  messages: object[]
  send?: () => unknown
  confirm?: () => unknown
  list?: object[]
}
let env: Env
let seen: { method: string; path: string; body: unknown }[]

function renderAt(path: string, role: 'ADMIN' | 'OPERATOR' = 'OPERATOR') {
  apiFetchMock.mockImplementation((p: string, options?: { method?: string; body?: unknown }) => {
    const method = options?.method ?? 'GET'
    seen.push({ method, path: p, body: options?.body })
    const result = route(p, method, options?.body)
    return result instanceof Error ? Promise.reject(result) : Promise.resolve(result)
  })
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/assistant" element={<ChatPage role={role} />} />
          <Route path="/assistant/:sessionId" element={<ChatPage role={role} />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

function route(p: string, method: string, body: unknown): unknown {
  if (method === 'GET' && p.startsWith('/ai/sessions?')) return { items: env.list ?? [session, other], page: 0, size: 50, totalElements: 2 }
  if (method === 'GET' && p === '/ai/sessions/s1') return { session, messages: env.messages }
  if (method === 'POST' && p === '/ai/sessions/s1/messages') return env.send ? env.send() : { runId: RUN }
  if (method === 'POST' && p.startsWith('/ai/sessions/s1/actions/')) return env.confirm ? env.confirm() : { messageId: 'm2', status: 'CONFIRMED', error: false, result: 'ok' }
  if (method === 'PATCH' && p === '/ai/sessions/s1') return { ...session, ...(body as object) }
  if (method === 'DELETE' && p === '/ai/sessions/s1') return null
  return new Error(`unmocked ${method} ${p}`)
}

const user = (id: string, seq: number, content: string) => ({ id, seq, role: 'USER', content, toolCalls: null, toolCallId: null, status: null, createdAt: '2026-10-23T10:00:00Z' })
const assistant = (id: string, seq: number, content: string) => ({ ...user(id, seq, content), role: 'ASSISTANT' })
const pendingTool = {
  id: 'm2', seq: 3, role: 'TOOL', content: 'Waiting for confirmation', status: 'PENDING', toolCallId: 'c1', createdAt: '2026-10-23T10:00:00Z',
  toolCalls: [{ id: 'c1', name: 'run_deployment', arguments: { hostId: 'h1', deploymentId: 'st1', env: '<hidden>' }, token: 't', description: 'Deploy "shop" on Local' }],
}

beforeEach(() => {
  seen = []
  env = { messages: [] }
  subscribe.mockReset()
  subscribe.mockImplementation((_d: string, h: TopicHandlers) => {
    handlers = h
    h.onState('connected')
    return vi.fn()
  })
})
afterEach(cleanup)

const calls = (method: string) => seen.filter((c) => c.method === method)

describe('ChatPage', () => {
  it('lists sessions and opens one with its history', async () => {
    env.messages = [user('m1', 1, 'What runs on Local?'), assistant('m2', 2, 'Three deployments.')]
    renderAt('/assistant/s1')
    expect(await screen.findByText('Three deployments.')).toBeInTheDocument()
    expect(screen.getByText('What runs on Local?')).toBeInTheDocument()
    const list = screen.getByRole('navigation', { name: 'Chats' })
    expect(within(list).getByRole('link', { name: /Old chat/ })).toHaveAttribute('href', '/assistant/s2')
    expect(subscribe).toHaveBeenCalledWith('/topic/chat/s1', expect.anything())
  })

  it('sends a message, shows streamed text and swaps to the stored answer on DONE', async () => {
    renderAt('/assistant/s1')
    fireEvent.change(await screen.findByLabelText('Message'), { target: { value: 'hello' } })
    fireEvent.click(screen.getByRole('button', { name: 'Send' }))
    expect(await screen.findByText('hello')).toBeInTheDocument()
    await waitFor(() => expect(calls('POST')[0].body).toEqual({ content: 'hello' }))
    frame({ runId: RUN, type: 'TOKEN', text: 'Hi ' })
    frame({ runId: RUN, type: 'TOKEN', text: 'there' })
    expect(screen.getByText('Hi there')).toBeInTheDocument()
    frame({ runId: RUN, type: 'TOOL_CALL', toolName: 'list_deployments' })
    expect(screen.getByText(/list deployments/i)).toBeInTheDocument()
    env.messages = [user('m1', 1, 'hello'), assistant('m2', 2, 'Hi there, stored')]
    frame({ runId: RUN, type: 'DONE', outcome: 'COMPLETE' })
    expect(await screen.findByText('Hi there, stored')).toBeInTheDocument()
    expect(screen.queryByText('Hi there')).not.toBeInTheDocument()
  })

  it('shows the not-configured state when the assistant has no provider', async () => {
    env.send = () => new ApiError(409, 'x', 'AI_NOT_CONFIGURED')
    renderAt('/assistant/s1', 'ADMIN')
    fireEvent.change(await screen.findByLabelText('Message'), { target: { value: 'hello' } })
    fireEvent.click(screen.getByRole('button', { name: 'Send' }))
    expect(await screen.findByText(/assistant is not set up/i)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Open AI settings' })).toHaveAttribute('href', '/settings/ai')
  })

  it('confirms a pending action through the API and shows what will happen', async () => {
    env.messages = [user('m1', 1, 'deploy shop'), pendingTool]
    renderAt('/assistant/s1')
    expect(await screen.findByText('Deploy "shop" on Local')).toBeInTheDocument()
    expect(screen.getByText('<hidden>')).toBeInTheDocument()
    expect(screen.getByLabelText('Message')).toBeDisabled()
    env.messages = [user('m1', 1, 'deploy shop'), { ...pendingTool, status: 'CONFIRMED' }]
    fireEvent.click(screen.getByRole('button', { name: 'Confirm' }))
    await waitFor(() => expect(calls('POST').some((c) => c.path === '/ai/sessions/s1/actions/m2/confirm')).toBe(true))
    expect(await screen.findByText('Confirmed')).toBeInTheDocument()
  })

  it('rejects a pending action through the API', async () => {
    env.messages = [user('m1', 1, 'deploy shop'), pendingTool]
    env.confirm = () => ({ messageId: 'm2', status: 'REJECTED', error: false, result: 'rejected' })
    renderAt('/assistant/s1')
    fireEvent.click(await screen.findByRole('button', { name: 'Reject' }))
    await waitFor(() => expect(calls('POST').some((c) => c.path === '/ai/sessions/s1/actions/m2/reject')).toBe(true))
  })

  it('toggles sharing with admins and explains it', async () => {
    renderAt('/assistant/s1')
    const toggle = await screen.findByRole('switch', { name: 'Share with admins' })
    expect(screen.getByText(/read-only/i)).toBeInTheDocument()
    fireEvent.click(toggle)
    await waitFor(() => expect(calls('PATCH')[0].body).toEqual({ sharedWithAdmin: true }))
  })

  it('renames and deletes a session', async () => {
    renderAt('/assistant/s1')
    fireEvent.click(await screen.findByRole('button', { name: 'Rename' }))
    const input = screen.getByLabelText('Chat title')
    fireEvent.change(input, { target: { value: 'Shop deploys' } })
    fireEvent.submit(input)
    await waitFor(() => expect(calls('PATCH')[0].body).toEqual({ title: 'Shop deploys' }))
    fireEvent.click(await screen.findByRole('button', { name: 'Delete chat' }))
    fireEvent.click(screen.getByRole('button', { name: 'Delete' }))
    await waitFor(() => expect(calls('DELETE')).toHaveLength(1))
  })
})
