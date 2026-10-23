import type { Page } from '@playwright/test'
import { expect, test, type ApiMock } from './fixtures/api.ts'

const RUN = '11111111-1111-1111-1111-111111111111'
const now = '2026-10-23T10:00:00Z'
const session = (id: string, title: string) => ({ id, title, sharedWithAdmin: false, createdAt: now, updatedAt: now, ownerUsername: null })
const message = (id: string, seq: number, role: string, content: string | null, extra: object = {}) => ({
  id, seq, role, content, toolCalls: null, toolCallId: null, status: null, createdAt: now, ...extra,
})
const pendingDeploy = message('m2', 2, 'TOOL', 'Waiting for confirmation', {
  status: 'PENDING',
  toolCallId: 'c1',
  toolCalls: [{ id: 'c1', name: 'deploy_stack', arguments: { stackId: 'st1', env: '<hidden>' }, token: 't', description: 'Deploy stack "shop" on Local' }],
})

const stompFrame = (command: string, headers: Record<string, string>, body = '') =>
  `${command}\n${Object.entries(headers).map(([k, v]) => `${k}:${v}`).join('\n')}\n\n${body}\0`

/** Minimal STOMP broker; `emit` pushes a frame to the chat topic the page subscribed to. */
async function mockBroker(page: Page) {
  let push: ((body: object) => void) | null = null
  const subscribed: string[] = []
  await page.routeWebSocket('**/ws', (ws) => {
    ws.onMessage((raw) => {
      const text = String(raw)
      if (text.startsWith('CONNECT') || text.startsWith('STOMP')) {
        ws.send(stompFrame('CONNECTED', { version: '1.2', 'heart-beat': '0,0' }))
      } else if (text.startsWith('SUBSCRIBE')) {
        const id = /id:(.*)/.exec(text)?.[1] ?? '0'
        const destination = /destination:(.*)/.exec(text)?.[1] ?? ''
        subscribed.push(destination)
        push = (body) =>
          ws.send(stompFrame('MESSAGE', { subscription: id, destination, 'message-id': String(Math.random()) }, JSON.stringify(body)))
      }
    })
  })
  return {
    subscribed,
    emit: async (body: object) => {
      await expect.poll(() => push !== null).toBe(true)
      push?.(body)
    },
  }
}

function signIn(api: ApiMock, role = 'OPERATOR') {
  api.mock('GET', '/api/auth/me', { body: { username: 'u', role, setupRequired: false } })
}

test('new chat: ask a question and read the streamed answer', async ({ page, api }) => {
  signIn(api)
  const broker = await mockBroker(page)
  api.mock('GET', '/api/ai/sessions', { body: { items: [], page: 0, size: 100, totalElements: 0 } })
  api.mock('POST', '/api/ai/sessions', { status: 201, body: session('n1', 'New chat') })
  api.mock('GET', '/api/ai/sessions/n1', { body: { session: session('n1', 'New chat'), messages: [] } })
  api.mock('POST', '/api/ai/sessions/n1/messages', { status: 202, body: { runId: RUN } })

  await page.goto('/assistant')
  await page.getByRole('button', { name: 'New chat' }).click()
  await expect(page).toHaveURL(/\/assistant\/n1$/)

  await page.getByLabel('Message').fill('How many stacks run?')
  await page.getByRole('button', { name: 'Send' }).click()
  await expect(page.getByText('How many stacks run?')).toBeVisible()

  await broker.emit({ runId: RUN, type: 'TOOL_CALL', toolName: 'list_stacks' })
  await expect(page.getByText('Using list stacks')).toBeVisible()
  await broker.emit({ runId: RUN, type: 'TOKEN', text: 'Two stacks ' })
  await broker.emit({ runId: RUN, type: 'TOKEN', text: 'are running.' })
  await expect(page.getByText('Two stacks are running.')).toBeVisible()

  api.mock('GET', '/api/ai/sessions/n1', {
    body: {
      session: session('n1', 'How many stacks run?'),
      messages: [message('m1', 1, 'USER', 'How many stacks run?'), message('m2', 2, 'ASSISTANT', 'Two stacks are running (stored).')],
    },
  })
  await broker.emit({ runId: RUN, type: 'DONE', outcome: 'COMPLETE' })
  await expect(page.getByText('Two stacks are running (stored).')).toBeVisible()
})

test('a pending deploy is shown and can be confirmed', async ({ page, api }) => {
  signIn(api)
  await mockBroker(page)
  const base = [message('m1', 1, 'USER', 'Deploy shop')]
  api.mock('GET', '/api/ai/sessions', { body: { items: [session('s1', 'Deploy shop')], page: 0, size: 100, totalElements: 1 } })
  api.mock('GET', '/api/ai/sessions/s1', { body: { session: session('s1', 'Deploy shop'), messages: [...base, pendingDeploy] } })
  api.mock('POST', '/api/ai/sessions/s1/actions/m2/confirm', { body: { messageId: 'm2', status: 'CONFIRMED', error: false, result: 'started' } })

  await page.goto('/assistant/s1')
  await expect(page.getByText('Deploy stack "shop" on Local')).toBeVisible()
  await expect(page.getByText('<hidden>')).toBeVisible()
  await expect(page.getByLabel('Message')).toBeDisabled()

  api.mock('GET', '/api/ai/sessions/s1', {
    body: { session: session('s1', 'Deploy shop'), messages: [...base, { ...pendingDeploy, status: 'CONFIRMED' }] },
  })
  await page.getByRole('button', { name: 'Confirm' }).click()
  await expect(page.getByText('Confirmed', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Confirm' })).toHaveCount(0)
})

test('reopen an old chat from the list and continue it', async ({ page, api }) => {
  signIn(api)
  const broker = await mockBroker(page)
  api.mock('GET', '/api/ai/sessions', {
    body: { items: [session('s1', 'Today'), session('s2', 'Old chat')], page: 0, size: 100, totalElements: 2 },
  })
  api.mock('GET', '/api/ai/sessions/s1', { body: { session: session('s1', 'Today'), messages: [] } })
  api.mock('GET', '/api/ai/sessions/s2', {
    body: { session: session('s2', 'Old chat'), messages: [message('m1', 1, 'USER', 'Earlier question'), message('m2', 2, 'ASSISTANT', 'Earlier answer')] },
  })
  api.mock('POST', '/api/ai/sessions/s2/messages', { status: 202, body: { runId: RUN } })

  await page.goto('/assistant/s1')
  await page.getByRole('navigation', { name: 'Chats' }).getByRole('link', { name: /Old chat/ }).click()
  await expect(page.getByText('Earlier answer')).toBeVisible()

  await page.getByLabel('Message').fill('And now?')
  await page.getByRole('button', { name: 'Send' }).click()
  api.mock('GET', '/api/ai/sessions/s2', {
    body: {
      session: session('s2', 'Old chat'),
      messages: [
        message('m1', 1, 'USER', 'Earlier question'),
        message('m2', 2, 'ASSISTANT', 'Earlier answer'),
        message('m3', 3, 'USER', 'And now?'),
        message('m4', 4, 'ASSISTANT', 'Continued answer'),
      ],
    },
  })
  await broker.emit({ runId: RUN, type: 'DONE', outcome: 'COMPLETE' })
  await expect(page.getByText('Continued answer')).toBeVisible()
  expect(broker.subscribed).toContain('/topic/chat/s2')
})

test('delete a chat', async ({ page, api }) => {
  signIn(api)
  await mockBroker(page)
  api.mock('GET', '/api/ai/sessions', { body: { items: [session('s1', 'Doomed chat')], page: 0, size: 100, totalElements: 1 } })
  api.mock('GET', '/api/ai/sessions/s1', { body: { session: session('s1', 'Doomed chat'), messages: [] } })
  api.mock('DELETE', '/api/ai/sessions/s1', { status: 204 })

  await page.goto('/assistant/s1')
  await page.getByRole('button', { name: 'Delete chat' }).click()
  api.mock('GET', '/api/ai/sessions', { body: { items: [], page: 0, size: 100, totalElements: 0 } })
  await page.getByRole('button', { name: 'Delete', exact: true }).click()
  await expect(page).toHaveURL(/\/assistant$/)
  await expect(page.getByText('No chats yet.')).toBeVisible()
})

test('shows the not-configured state when no provider is set up', async ({ page, api }) => {
  signIn(api, 'ADMIN')
  await mockBroker(page)
  api.mock('GET', '/api/ai/sessions', { body: { items: [session('s1', 'Chat')], page: 0, size: 100, totalElements: 1 } })
  api.mock('GET', '/api/ai/sessions/s1', { body: { session: session('s1', 'Chat'), messages: [] } })
  api.mock('POST', '/api/ai/sessions/s1/messages', { status: 409, body: { code: 'AI_NOT_CONFIGURED', message: 'not configured' } })

  await page.goto('/assistant/s1')
  await page.getByLabel('Message').fill('hello')
  await page.getByRole('button', { name: 'Send' }).click()
  await expect(page.getByText('The assistant is not set up yet.')).toBeVisible()
  await expect(page.getByRole('link', { name: 'Open AI settings' })).toBeVisible()
})
