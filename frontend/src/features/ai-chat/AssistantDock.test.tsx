import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { Link, MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { apiFetch } from '../../shared/api/client.ts'
import type { TopicHandlers } from '../../shared/realtime/stomp.ts'

vi.mock('../../shared/api/client.ts', async (importActual) => ({
  ...(await importActual<typeof import('../../shared/api/client.ts')>()),
  apiFetch: vi.fn(),
}))
const subscribe = vi.fn()
vi.mock('../../shared/realtime/stomp.ts', () => ({ subscribeToTopic: (...a: unknown[]) => subscribe(...a) }))

import { AssistantDock } from './AssistantDock.tsx'
import { ChatDockProvider } from './ChatDockProvider.tsx'
import { useChatDock } from './useChatDock.ts'

const HOST = '11111111-1111-1111-1111-111111111111'
const DEP = '22222222-2222-2222-2222-222222222222'
const stamp = '2026-10-23T10:00:00Z'
const session = (id: string, title: string) => ({ id, title, sharedWithAdmin: false, createdAt: stamp, updatedAt: stamp, ownerUsername: null })

let handlers: TopicHandlers
let sessions: ReturnType<typeof session>[]
let messages: object[]
const frame = (body: object) => act(() => handlers.onMessage(JSON.stringify(body)))

function Unread() {
  return <p data-testid="unread">{String(useChatDock().unread)}</p>
}

function renderAt(path: string) {
  vi.mocked(apiFetch).mockImplementation((p: string, options?: { method?: string }) => {
    const method = options?.method ?? 'GET'
    if (method === 'GET' && p.startsWith('/ai/sessions?')) return Promise.resolve({ items: sessions })
    if (method === 'POST' && p === '/ai/sessions') return Promise.resolve(session('n1', 'New chat'))
    if (method === 'GET' && p === '/ai/sessions/pending-count') {
      return Promise.resolve({ count: messages.filter((m) => (m as { status: string | null }).status === 'PENDING').length })
    }
    if (method === 'GET' && p.startsWith('/ai/sessions/')) {
      const id = p.split('/')[3]
      return Promise.resolve({ session: sessions.find((s) => s.id === id) ?? session(id, 'New chat'), messages })
    }
    if (method === 'POST' && p.endsWith('/messages')) return Promise.resolve({ runId: 'r' })
    return Promise.reject(new Error(`unmocked ${method} ${p}`))
  })
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <ChatDockProvider>
          <AssistantDock isAdmin={false} narrow={false} />
          <Unread />
          <Link to={`/environments/${HOST}/images`}>go images</Link>
        </ChatDockProvider>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

const icon = () => screen.getByRole('button', { name: 'Open assistant' })

beforeEach(() => {
  sessions = [session('s1', 'Deploy help'), session('s2', 'Old chat')]
  messages = []
  subscribe.mockReset()
  subscribe.mockImplementation((_d: string, h: TopicHandlers) => {
    handlers = h
    h.onState('connected')
    return vi.fn()
  })
})
afterEach(cleanup)

describe('AssistantDock', () => {
  it('shows a keyboard reachable icon with a name on normal pages', () => {
    renderAt('/containers')
    expect(icon()).toBeInTheDocument()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('is hidden on the full size assistant pages', () => {
    renderAt('/assistant/s1')
    expect(screen.queryByRole('button', { name: 'Open assistant' })).not.toBeInTheDocument()
  })

  it('opens a panel with the same sessions as the assistant page and moves focus into it', async () => {
    renderAt('/containers')
    fireEvent.click(icon())
    const panel = await screen.findByRole('dialog', { name: 'Assistant' })
    expect(panel).toContainElement(document.activeElement as HTMLElement)
    const picker = await within(panel).findByRole('combobox', { name: 'Chat' })
    await waitFor(() => expect(within(picker).getAllByRole('option').map((o) => o.textContent)).toEqual(expect.arrayContaining(['Deploy help', 'Old chat'])))
  })

  it('opens the picked chat and links to its full size page', async () => {
    renderAt('/containers')
    fireEvent.click(icon())
    const picker = await screen.findByRole('combobox', { name: 'Chat' })
    await within(picker).findByRole('option', { name: 'Old chat' })
    fireEvent.change(picker, { target: { value: 's2' } })
    expect(await screen.findByLabelText('Message')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Open full size' })).toHaveAttribute('href', '/assistant/s2')
  })

  it('starts a new chat from the panel', async () => {
    renderAt('/containers')
    fireEvent.click(icon())
    fireEvent.click(await screen.findByRole('button', { name: 'New chat' }))
    expect(await screen.findByLabelText('Message')).toBeInTheDocument()
    expect(vi.mocked(apiFetch)).toHaveBeenCalledWith('/ai/sessions', expect.objectContaining({ method: 'POST' }))
    expect(screen.getByRole('link', { name: 'Open full size' })).toHaveAttribute('href', '/assistant/n1')
  })

  it('shows a streamed answer after asking', async () => {
    renderAt('/containers')
    fireEvent.click(icon())
    fireEvent.click(await screen.findByRole('button', { name: 'New chat' }))
    fireEvent.change(await screen.findByLabelText('Message'), { target: { value: 'hello' } })
    fireEvent.click(screen.getByRole('button', { name: 'Send' }))
    await waitFor(() => expect(vi.mocked(apiFetch)).toHaveBeenCalledWith('/ai/sessions/n1/messages', expect.anything()))
    frame({ type: 'TOKEN', text: 'Two deployments run.' })
    expect(await screen.findByText('Two deployments run.')).toBeInTheDocument()
  })

  it('closes with Escape and returns focus to the icon', async () => {
    renderAt('/containers')
    fireEvent.click(icon())
    const panel = await screen.findByRole('dialog', { name: 'Assistant' })
    fireEvent.keyDown(panel, { key: 'Escape' })
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(icon()).toHaveFocus()
  })

  it('closes with the close button', async () => {
    renderAt('/containers')
    fireEvent.click(icon())
    fireEvent.click(await screen.findByRole('button', { name: 'Close assistant' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(icon()).toHaveFocus()
  })

  it('only counts as viewing the chat while the panel is open', async () => {
    renderAt('/containers')
    fireEvent.click(icon())
    fireEvent.click(await screen.findByRole('button', { name: 'New chat' }))
    await screen.findByLabelText('Message')
    fireEvent.keyDown(screen.getByRole('dialog'), { key: 'Escape' })
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    frame({ type: 'TOKEN', text: 'x' })
    frame({ type: 'DONE', outcome: 'COMPLETE' })
    await waitFor(() => expect(screen.getByTestId('unread')).toHaveTextContent('true'))
    fireEvent.click(icon())
    await screen.findByRole('dialog')
    await waitFor(() => expect(screen.getByTestId('unread')).toHaveTextContent('false'))
  })

  async function askThenClose() {
    fireEvent.click(icon())
    fireEvent.click(await screen.findByRole('button', { name: 'New chat' }))
    fireEvent.change(await screen.findByLabelText('Message'), { target: { value: 'hello' } })
    fireEvent.click(screen.getByRole('button', { name: 'Send' }))
    await waitFor(() => expect(vi.mocked(apiFetch)).toHaveBeenCalledWith('/ai/sessions/n1/messages', expect.anything()))
    fireEvent.keyDown(screen.getByRole('dialog'), { key: 'Escape' })
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
  }
  it('closes with Escape after sending a message even though focus left the panel', async () => {
    renderAt('/containers')
    fireEvent.click(icon())
    fireEvent.click(await screen.findByRole('button', { name: 'New chat' }))
    fireEvent.change(await screen.findByLabelText('Message'), { target: { value: 'hello' } })
    fireEvent.click(screen.getByRole('button', { name: 'Send' }))
    await waitFor(() => expect(screen.getByLabelText('Message')).toBeDisabled())
    ;(document.activeElement as HTMLElement | null)?.blur()
    fireEvent.keyDown(document.body, { key: 'Escape' })
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(icon()).toHaveFocus()
  })

  it('leaves Escape alone when something inside already handled it', async () => {
    renderAt('/containers')
    fireEvent.click(icon())
    const panel = await screen.findByRole('dialog', { name: 'Assistant' })
    const handled = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true })
    handled.preventDefault()
    panel.dispatchEvent(handled)
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })

  const status = () => screen.getByTestId('assistant-dock-status')

  it('shows a spinner and announces while an answer runs with the panel closed', async () => {
    renderAt('/containers')
    expect(icon()).toHaveAttribute('data-status', 'idle')
    expect(status()).toHaveTextContent('')
    await askThenClose()
    await waitFor(() => expect(icon()).toHaveAttribute('data-status', 'running'))
    expect(icon().querySelector('.assistant-dock__spinner')).not.toBeNull()
    expect(status()).toHaveTextContent('The assistant is answering')
    expect(icon()).toHaveAccessibleDescription('The assistant is answering')
  })

  it('shows an answer-ready dot after DONE while closed and clears it when opened', async () => {
    renderAt('/containers')
    await askThenClose()
    frame({ type: 'TOKEN', text: 'x' })
    frame({ type: 'DONE', outcome: 'COMPLETE' })
    await waitFor(() => expect(icon()).toHaveAttribute('data-status', 'ready'))
    expect(icon().querySelector('.assistant-dock__dot')).not.toBeNull()
    expect(icon().querySelector('.assistant-dock__spinner')).toBeNull()
    expect(status()).toHaveTextContent('The assistant has an answer')
    fireEvent.click(icon())
    await screen.findByRole('dialog')
    fireEvent.keyDown(screen.getByRole('dialog'), { key: 'Escape' })
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(icon()).toHaveAttribute('data-status', 'idle')
    expect(status()).toHaveTextContent('')
  })

  it('shows a count badge for pending actions that only opens the panel', async () => {
    messages = [
      { id: 'm1', seq: 1, role: 'USER', content: 'deploy', status: null, createdAt: stamp },
      ...[2, 3].map((n) => ({
        id: `m${n}`, seq: n, role: 'TOOL', content: 'Waiting', status: 'PENDING', toolCallId: `c${n}`, createdAt: stamp,
        toolCalls: [{ id: `c${n}`, name: 'run_deployment', arguments: {}, token: 't', description: 'Deploy "shop"' }],
      })),
    ]
    renderAt('/containers')
    fireEvent.click(icon())
    const picker = await screen.findByRole('combobox', { name: 'Chat' })
    await within(picker).findByRole('option', { name: 'Deploy help' })
    fireEvent.change(picker, { target: { value: 's1' } })
    await waitFor(() => expect(vi.mocked(apiFetch)).toHaveBeenCalledWith('/ai/sessions/s1'))
    fireEvent.keyDown(screen.getByRole('dialog'), { key: 'Escape' })
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    await waitFor(() => expect(icon().querySelector('.assistant-dock__badge')).toHaveTextContent('2'))
    expect(status()).toHaveTextContent('2 actions are waiting for your confirmation')
    const writes = vi.mocked(apiFetch).mock.calls.length
    fireEvent.click(icon())
    expect(await screen.findByRole('dialog')).toBeInTheDocument()
    expect(vi.mocked(apiFetch).mock.calls.slice(writes).some(([, o]) => (o as { method?: string } | undefined)?.method === 'POST')).toBe(false)
  })

  describe('view context', () => {
    const sentBodies = () =>
      vi.mocked(apiFetch).mock.calls.filter(([p]) => p === '/ai/sessions/n1/messages').map(([, o]) => (o as { body: object }).body)

    async function openNewChat() {
      fireEvent.click(icon())
      fireEvent.click(await screen.findByRole('button', { name: 'New chat' }))
      return screen.findByLabelText('Message')
    }
    // Waits until this message's POST has been issued. The stream is not finished in these tests, so the
    // input stays busy; a missing send fails the waitFor instead of being swallowed.
    const ask = async (text: string) => {
      const before = sentBodies().length
      fireEvent.change(await screen.findByLabelText('Message'), { target: { value: text } })
      fireEvent.click(screen.getByRole('button', { name: 'Send' }))
      await waitFor(() => expect(sentBodies()).toHaveLength(before + 1))
    }

    it('sends host and deployment ids and shows a chip on a deployment page', async () => {
      renderAt(`/environments/${HOST}/deployments/${DEP}`)
      await openNewChat()
      expect(screen.getByText(/Viewing:/)).toBeInTheDocument()
      await ask('why is it down?')
      await waitFor(() => expect(sentBodies()).toEqual([{ content: 'why is it down?', context: { page: 'DEPLOYMENT', hostId: HOST, deploymentId: DEP } }]))
    })

    it('sends no context for that message after the chip was removed, then shows it again', async () => {
      renderAt(`/environments/${HOST}/deployments/${DEP}`)
      await openNewChat()
      fireEvent.click(screen.getByRole('button', { name: /Remove view context/ }))
      expect(screen.queryByText(/Viewing:/)).not.toBeInTheDocument()
      await ask('first')
      await waitFor(() => expect(sentBodies()).toEqual([{ content: 'first' }]))
      expect(await screen.findByText(/Viewing:/)).toBeInTheDocument()
    })

    it('follows navigation between messages', async () => {
      renderAt(`/environments/${HOST}/deployments/${DEP}`)
      await openNewChat()
      await ask('one')
      await waitFor(() => expect(sentBodies()).toHaveLength(1))
      fireEvent.click(screen.getByRole('link', { name: 'go images' }))
      frame({ type: 'DONE', outcome: 'COMPLETE' })
      await waitFor(() => expect(screen.getByLabelText('Message')).toBeEnabled())
      await ask('two')
      await waitFor(() => expect(sentBodies()[1]).toEqual({ content: 'two', context: { page: 'IMAGES', hostId: HOST } }))
    })

    it('shows no chip and sends no context on other pages', async () => {
      renderAt('/containers')
      await openNewChat()
      expect(screen.queryByText(/Viewing:/)).not.toBeInTheDocument()
      await ask('hi')
      await waitFor(() => expect(sentBodies()).toEqual([{ content: 'hi' }]))
    })
  })

  it('pads the phone sheet by the bottom safe-area inset', () => {
    const css = readFileSync(join(process.cwd(), 'src/features/ai-chat/AssistantDock.css'), 'utf8')
    const sheet = /\.assistant-dock__panel--sheet\s*\{[^}]*\}/.exec(css)?.[0] ?? ''
    expect(sheet).toContain('env(safe-area-inset-bottom')
  })

  describe('left and right safe-area insets (landscape notch)', () => {
    const css = readFileSync(join(process.cwd(), 'src/features/ai-chat/AssistantDock.css'), 'utf8')
    const block = (selector: string) => new RegExp(`${selector}\\s*\\{[^}]*\\}`).exec(css)?.[0] ?? ''

    it('offsets the dock icon from the right inset', () => {
      expect(block('\\.assistant-dock')).toContain('var(--safe-right)')
    })

    it('keeps the open panel clear of the left inset', () => {
      expect(block('\\.assistant-dock__panel')).toContain('var(--safe-left)')
    })

    it('pads the phone sheet by the left and right insets', () => {
      const sheet = block('\\.assistant-dock__panel--sheet')
      expect(sheet).toContain('var(--safe-left)')
      expect(sheet).toContain('var(--safe-right)')
    })
  })
})
