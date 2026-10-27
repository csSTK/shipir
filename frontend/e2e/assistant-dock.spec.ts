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
