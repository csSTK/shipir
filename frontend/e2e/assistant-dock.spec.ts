import type { Page } from '@playwright/test'
import { expect, test, type ApiMock } from './fixtures/api.ts'

const RUN = '11111111-1111-1111-1111-111111111111'
const now = '2026-10-23T10:00:00Z'
const session = (id: string, title: string) => ({ id, title, sharedWithAdmin: false, createdAt: now, updatedAt: now, ownerUsername: null })
const list = (...items: object[]) => ({ items, page: 0, size: 100, totalElements: items.length })

const stompFrame = (command: string, headers: Record<string, string>, body = '') =>
  `${command}\n${Object.entries(headers).map(([k, v]) => `${k}:${v}`).join('\n')}\n\n${body}\0`

/** Minimal STOMP broker; `emit` pushes a frame to the chat topic the page subscribed to. */
async function mockBroker(page: Page) {
  let push: ((body: object) => void) | null = null
  await page.routeWebSocket('**/ws', (ws) => {
    ws.onMessage((raw) => {
      const text = String(raw)
      if (text.startsWith('CONNECT') || text.startsWith('STOMP')) {
        ws.send(stompFrame('CONNECTED', { version: '1.2', 'heart-beat': '0,0' }))
      } else if (text.startsWith('SUBSCRIBE')) {
        const id = /id:(.*)/.exec(text)?.[1] ?? '0'
        const destination = /destination:(.*)/.exec(text)?.[1] ?? ''
        push = (body) =>
          ws.send(stompFrame('MESSAGE', { subscription: id, destination, 'message-id': String(Math.random()) }, JSON.stringify(body)))
      }
    })
  })
  return {
    emit: async (body: object) => {
      await expect.poll(() => push !== null).toBe(true)
      push?.(body)
    },
  }
}

function setup(api: ApiMock) {
  api.mock('GET', '/api/hosts/overview', { body: [] })
  api.mock('GET', '/api/auth/me', { body: { username: 'u', role: 'OPERATOR', setupRequired: false } })
  api.mock('GET', '/api/ai/sessions', { body: list(session('s1', 'Deploy help')) })
  api.mock('GET', '/api/ai/sessions/s1', { body: { session: session('s1', 'Deploy help'), messages: [] } })
  api.mock('POST', '/api/ai/sessions/s1/messages', { status: 202, body: { runId: RUN } })
}

test('open the dock, ask a question and read the streamed answer', async ({ page, api }) => {
  setup(api)
  const broker = await mockBroker(page)
  await page.goto('/')

  await page.getByRole('button', { name: 'Open assistant' }).click()
  const panel = page.getByRole('dialog', { name: 'Assistant' })
  await expect(panel).toBeVisible()
  await panel.getByRole('combobox', { name: 'Chat' }).selectOption('s1')
  await expect(panel.getByRole('link', { name: 'Open full size' })).toHaveAttribute('href', '/assistant/s1')

  await panel.getByLabel('Message').fill('How many deployments run?')
  await panel.getByRole('button', { name: 'Send' }).click()
  await expect(panel.getByText('How many deployments run?')).toBeVisible()
  await broker.emit({ runId: RUN, type: 'TOKEN', text: 'Two deployments ' })
  await broker.emit({ runId: RUN, type: 'TOKEN', text: 'are running.' })
  await expect(panel.getByText('Two deployments are running.')).toBeVisible()
})

test('Escape closes the dock and focus returns to the icon', async ({ page, api }) => {
  setup(api)
  await mockBroker(page)
  await page.goto('/')
  await page.getByRole('button', { name: 'Open assistant' }).click()
  await expect(page.getByRole('dialog', { name: 'Assistant' })).toBeVisible()
  await page.keyboard.press('Escape')
  await expect(page.getByRole('dialog')).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Open assistant' })).toBeFocused()
})

test('the icon is hidden on the full size assistant page and "Open full size" goes there', async ({ page, api }) => {
  setup(api)
  await mockBroker(page)
  await page.goto('/')
  await page.getByRole('button', { name: 'Open assistant' }).click()
  await page.getByRole('combobox', { name: 'Chat' }).selectOption('s1')
  await page.getByRole('link', { name: 'Open full size' }).click()
  await expect(page).toHaveURL(/\/assistant\/s1$/)
  await expect(page.getByRole('button', { name: 'Open assistant' })).toHaveCount(0)
  await expect(page.getByRole('dialog', { name: 'Assistant' })).toHaveCount(0)
})

test('on a phone the dock is a bottom sheet', async ({ page, api }) => {
  setup(api)
  await mockBroker(page)
  await page.setViewportSize({ width: 390, height: 800 })
  await page.goto('/')
  await page.getByRole('button', { name: 'Open assistant' }).click()
  const panel = page.getByRole('dialog', { name: 'Assistant' })
  await expect(panel).toBeVisible()
  await expect.poll(async () => (await panel.boundingBox())?.width).toBe(390)
  await expect.poll(async () => Math.round(((await panel.boundingBox())?.y ?? 0) + ((await panel.boundingBox())?.height ?? 0))).toBe(800)
})

test('reduced motion still opens and closes the dock', async ({ page, api }) => {
  setup(api)
  await mockBroker(page)
  await page.emulateMedia({ reducedMotion: 'reduce' })
  await page.goto('/')
  await page.getByRole('button', { name: 'Open assistant' }).click()
  await expect(page.getByRole('dialog', { name: 'Assistant' })).toBeVisible()
  await page.getByRole('button', { name: 'Close assistant' }).click()
  await expect(page.getByRole('dialog')).toHaveCount(0)
})

test('navigate away mid-answer: the icon spins, then shows an answer-ready dot until the chat is opened', async ({ page, api }) => {
  setup(api)
  const broker = await mockBroker(page)
  await page.goto('/')
  await page.getByRole('button', { name: 'Open assistant' }).click()
  const panel = page.getByRole('dialog', { name: 'Assistant' })
  await panel.getByRole('combobox', { name: 'Chat' }).selectOption('s1')
  await panel.getByLabel('Message').fill('How many deployments run?')
  await panel.getByRole('button', { name: 'Send' }).click()
  await broker.emit({ runId: RUN, type: 'TOKEN', text: 'Let me look' })
  await expect(panel.getByText('Let me look')).toBeVisible()

  // Full page, then away to another page: the answer keeps streaming and only the icon reports it.
  await panel.getByRole('link', { name: 'Open full size' }).click()
  await expect(page).toHaveURL(/\/assistant\/s1$/)
  await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Environments' }).click()
  await expect(page).toHaveURL(/\/$/)
  const icon = page.getByRole('button', { name: 'Open assistant' })
  await expect(icon).toHaveAttribute('data-status', 'running')
  await expect(page.getByRole('status')).toHaveText('The assistant is answering')

  await broker.emit({ runId: RUN, type: 'DONE', outcome: 'COMPLETE' })
  await expect(icon).toHaveAttribute('data-status', 'ready')
  await expect(page.getByRole('status')).toHaveText('The assistant has an answer')

  await icon.click()
  await expect(page.getByRole('dialog', { name: 'Assistant' })).toBeVisible()
  await page.keyboard.press('Escape')
  await expect(icon).toHaveAttribute('data-status', 'idle')
})

test('a pending action shows a count badge on the icon; clicking it only opens the panel', async ({ page, api }) => {
  setup(api)
  api.mock('GET', '/api/ai/sessions/s1', {
    body: {
      session: session('s1', 'Deploy help'),
      messages: [
        {
          id: 'm2', seq: 2, role: 'TOOL', content: 'Waiting', status: 'PENDING', toolCallId: 'c1', createdAt: now,
          toolCalls: [{ id: 'c1', name: 'run_deployment', arguments: {}, token: 't', description: 'Deploy "shop"' }],
        },
      ],
    },
  })
  // No confirm/reject mock on purpose: the fixture fails the test if the badge click resolved the action.
  await mockBroker(page)
  await page.goto('/')
  await page.getByRole('button', { name: 'Open assistant' }).click()
  await page.getByRole('combobox', { name: 'Chat' }).selectOption('s1')
  await page.keyboard.press('Escape')
  const icon = page.getByRole('button', { name: 'Open assistant' })
  await expect(icon).toHaveAttribute('data-status', 'pending')
  await expect(icon.getByText('1', { exact: true })).toBeVisible()
  await expect(page.getByRole('status')).toHaveText('1 action is waiting for your confirmation')

  await icon.click()
  await expect(page.getByRole('dialog', { name: 'Assistant' })).toBeVisible()
})

test('reduced motion: the running icon does not spin', async ({ page, api }) => {
  setup(api)
  const broker = await mockBroker(page)
  await page.emulateMedia({ reducedMotion: 'reduce' })
  await page.goto('/')
  await page.getByRole('button', { name: 'Open assistant' }).click()
  const panel = page.getByRole('dialog', { name: 'Assistant' })
  await panel.getByRole('combobox', { name: 'Chat' }).selectOption('s1')
  await panel.getByLabel('Message').fill('hi')
  await panel.getByRole('button', { name: 'Send' }).click()
  await broker.emit({ runId: RUN, type: 'TOKEN', text: 'x' })
  await page.keyboard.press('Escape')
  const spinner = page.locator('.assistant-dock__spinner')
  await expect(spinner).toBeVisible()
  await expect(spinner).toHaveCSS('animation-name', 'none')
})

const HOST = '11111111-1111-1111-1111-111111111111'

/** Reads the JSON body of the next POST to the chat messages endpoint. */
function nextMessageBody(page: Page) {
  return page.waitForRequest((r) => r.method() === 'POST' && r.url().endsWith('/api/ai/sessions/s1/messages')).then((r) => r.postDataJSON())
}

test('the chip shows what the assistant sees and context follows navigation between messages', async ({ page, api }) => {
  setup(api)
  api.mock('GET', `/api/hosts/${HOST}/images`, { body: [] })
  const broker = await mockBroker(page)
  await page.goto('/')
  await page.getByRole('button', { name: 'Open assistant' }).click()
  const panel = page.getByRole('dialog', { name: 'Assistant' })
  await panel.getByRole('combobox', { name: 'Chat' }).selectOption('s1')
  await expect(panel.getByText('Viewing: Environments')).toBeVisible()

  const first = nextMessageBody(page)
  await panel.getByLabel('Message').fill('one')
  await panel.getByRole('button', { name: 'Send' }).click()
  expect(await first).toEqual({ content: 'one', context: { page: 'OVERVIEW' } })
  await broker.emit({ runId: RUN, type: 'DONE', outcome: 'COMPLETE' })

  // Client-side navigation: the panel and its chat stay open.
  await page.evaluate((path) => {
    history.pushState({}, '', path)
    window.dispatchEvent(new PopStateEvent('popstate'))
  }, `/environments/${HOST}/images`)
  await expect(panel.getByText('Viewing: Images')).toBeVisible()
  const second = nextMessageBody(page)
  await panel.getByLabel('Message').fill('two')
  await panel.getByRole('button', { name: 'Send' }).click()
  expect(await second).toEqual({ content: 'two', context: { page: 'IMAGES', hostId: HOST } })
})

test('removing the chip sends the message without context', async ({ page, api }) => {
  setup(api)
  await mockBroker(page)
  await page.goto('/')
  await page.getByRole('button', { name: 'Open assistant' }).click()
  const panel = page.getByRole('dialog', { name: 'Assistant' })
  await panel.getByRole('combobox', { name: 'Chat' }).selectOption('s1')
  await panel.getByRole('button', { name: /Remove view context/ }).click()
  await expect(panel.getByText(/Viewing:/)).toHaveCount(0)
  const body = nextMessageBody(page)
  await panel.getByLabel('Message').fill('no context please')
  await panel.getByRole('button', { name: 'Send' }).click()
  expect(await body).toEqual({ content: 'no context please' })
})
