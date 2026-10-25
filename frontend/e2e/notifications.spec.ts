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
  await expect(page.getByText('Staging is unreachable (a)')).toBeVisible()
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
        { ...item('a', false), type: 'IMAGE_UPDATE_APPLIED', message: 'shop images updated' },
        { ...item('b', false), type: 'IMAGE_UPDATE_FAILED', message: 'shop image update failed' },
      ],
      totalElements: 2,
    },
  })
  await page.goto('/notifications')
  await expect(page.getByText('shop images updated')).toBeVisible()
  await expect(page.getByText('Image update failed', { exact: true })).toBeVisible()
})
