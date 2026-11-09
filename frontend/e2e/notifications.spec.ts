import { expect, test } from './fixtures/api.ts'

const item = (id: string, read: boolean, hostId: string | null = 'h1') => ({
  id,
  type: 'HOST_DOWN',
  hostId,
  hostName: 'Staging',
  message: `Staging is unreachable (${id})`,
  read,
  createdAt: new Date().toISOString(),
})
const me = { username: 'u', role: 'VIEWER', setupRequired: false }

test.beforeEach(({ api }) => {
  api.mock('GET', '/api/auth/me', { body: me })
  api.mock('GET', '/api/hosts/overview', { body: [] })
})

test('badge shows the unread count and opening the page lists notifications', async ({ page, api }) => {
  api.mock('GET', '/api/notifications/unread-count', { body: { count: 2 } })
  api.mock('GET', '/api/notifications', {
    body: { content: [item('a', false), item('b', false)], totalElements: 2 },
  })
  await page.goto('/')
  await expect(page.getByLabel('2 unread')).toBeVisible()
  await page.getByRole('link', { name: /^Notifications/ }).click()
  await expect(page.getByText('Environment "Staging" is down.').first()).toBeVisible()
})

test('mark as read lowers the badge, mark all read clears it', async ({ page, api }) => {
  api.mock('GET', '/api/notifications/unread-count', { body: { count: 2 } })
  api.mock('GET', '/api/notifications', {
    body: { content: [item('a', false), item('b', false)], totalElements: 2 },
  })
  api.mock('POST', '/api/notifications/a/read')
  api.mock('POST', '/api/notifications/read-all')
  await page.goto('/notifications')
  await expect(page.getByLabel('2 unread')).toBeVisible()

  api.mock('GET', '/api/notifications/unread-count', { body: { count: 1 } })
  api.mock('GET', '/api/notifications', {
    body: { content: [item('a', true), item('b', false)], totalElements: 2 },
  })
  await page.getByRole('button', { name: 'Mark as read' }).first().click()
  await expect(page.getByLabel('1 unread')).toBeVisible()

  api.mock('GET', '/api/notifications/unread-count', { body: { count: 0 } })
  api.mock('GET', '/api/notifications', {
    body: { content: [item('a', true), item('b', true)], totalElements: 2 },
  })
  await page.getByRole('button', { name: 'Mark all as read' }).click()
  await expect(page.getByLabel(/unread$/)).toHaveCount(0)
})

test('a deployment failure is labelled and links to the deployment', async ({ page, api }) => {
  api.mock('GET', '/api/notifications/unread-count', { body: { count: 1 } })
  api.mock('GET', '/api/notifications', {
    body: {
      content: [
        {
          ...item('d', false),
          type: 'DEPLOYMENT_FAILED',
          message: 'web failed to deploy',
          deploymentId: 'd1',
          deploymentName: 'web',
        },
      ],
      totalElements: 1,
    },
  })
  await page.goto('/notifications')
  await expect(page.getByText(/Deployment failed/)).toBeVisible()
  await expect(page.getByRole('link', { name: 'web' })).toHaveAttribute('href', '/environments/h1/deployments/d1')
})

test('image update notifications render without crashing', async ({ page, api }) => {
  api.mock('GET', '/api/notifications/unread-count', { body: { count: 2 } })
  api.mock('GET', '/api/notifications', {
    body: {
      content: [
        { ...item('a', false), type: 'IMAGE_UPDATE_APPLIED', message: 'shop images updated', deploymentId: 'd1', deploymentName: 'shop' },
        { ...item('b', false), type: 'IMAGE_UPDATE_FAILED', message: 'shop image update failed' },
      ],
      totalElements: 2,
    },
  })
  await page.goto('/notifications')
  await expect(page.getByText('Image update applied to "shop".')).toBeVisible()
  await expect(page.getByText('Image update failed', { exact: true })).toBeVisible()
})

// Layout (shipr-ycvc.98): no visual baselines on this branch, so these check geometry and roles instead.
test('the header controls share one center line', async ({ page, api }) => {
  api.mock('GET', '/api/notifications/unread-count', { body: { count: 1 } })
  api.mock('GET', '/api/notifications', { body: { content: [item('a', false)], totalElements: 1 } })
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.goto('/notifications')
  const center = async (box: { y: number; height: number } | null) => (box ? box.y + box.height / 2 : NaN)
  const title = await center(await page.getByRole('heading', { name: 'Notifications' }).boundingBox())
  const filter = await center(await page.getByRole('checkbox', { name: 'Unread only' }).boundingBox())
  const markAll = await center(await page.getByRole('button', { name: 'Mark all as read' }).boundingBox())
  expect(Math.abs(filter - title)).toBeLessThanOrEqual(2)
  expect(Math.abs(markAll - title)).toBeLessThanOrEqual(2)
})

test.describe('phone layout', () => {
  test.use({ viewport: { width: 375, height: 800 } })

  test('Mark as read sits at the right edge of the card row, not mid-row', async ({ page, api }) => {
    api.mock('GET', '/api/notifications/unread-count', { body: { count: 1 } })
    api.mock('GET', '/api/notifications', { body: { content: [item('a', false)], totalElements: 1 } })
    await page.goto('/notifications')
    const card = await page.getByRole('listitem').filter({ has: page.getByRole('button', { name: 'Mark as read' }) }).boundingBox()
    const button = await page.getByRole('button', { name: 'Mark as read' }).boundingBox()
    if (!card || !button) throw new Error('card or button not rendered')
    // Card padding is --space-4 (16px) on the right; the button's right edge sits on that inner edge.
    expect(Math.abs(card.x + card.width - 16 - (button.x + button.width))).toBeLessThanOrEqual(2)
  })

  test('the header actions stay inside the viewport at 375px', async ({ page, api }) => {
    api.mock('GET', '/api/notifications/unread-count', { body: { count: 1 } })
    api.mock('GET', '/api/notifications', { body: { content: [item('a', false)], totalElements: 1 } })
    await page.goto('/notifications')
    const markAll = await page.getByRole('button', { name: 'Mark all as read' }).boundingBox()
    expect(markAll && markAll.x + markAll.width).toBeLessThanOrEqual(375)
  })
})

test('read and unread cards are visually different', async ({ page, api }) => {
  api.mock('GET', '/api/notifications/unread-count', { body: { count: 1 } })
  api.mock('GET', '/api/notifications', { body: { content: [item('a', false), item('b', true)], totalElements: 2 } })
  await page.goto('/notifications')
  const unread = page.getByRole('listitem').filter({ has: page.getByRole('button', { name: 'Mark as read' }) })
  const read = page.getByRole('listitem').filter({ hasNot: page.getByRole('button', { name: 'Mark as read' }) })
  const accentOf = (locator: typeof unread) => locator.evaluate((el) => getComputedStyle(el).borderLeftWidth)
  expect(await accentOf(unread)).not.toEqual(await accentOf(read))
})
