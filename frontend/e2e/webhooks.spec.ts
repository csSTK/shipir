import { expect, test } from './fixtures/api.ts'

const hook = {
  id: 'w1',
  name: 'Ops chat',
  urlHint: 'https://hooks.example.com/…x9f2',
  eventTypes: ['HOST_DOWN'],
  enabled: true,
  lastDeliveryAt: null,
  lastDeliveryStatus: null,
}
const user = (role: string) => ({ username: 'u', role, setupRequired: false })

test('admin creates a webhook and sends a test', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', { body: user('ADMIN') })
  api.mock('GET', '/api/notifications/webhooks', { body: [hook] })
  api.mock('POST', '/api/notifications/webhooks', { status: 201, body: { ...hook, id: 'w2', name: 'Pager' } })
  api.mock('POST', '/api/notifications/webhooks/w1/test', { body: { ok: true, status: 'SUCCESS' } })
  await page.goto('/settings/notifications')

  await expect(page.getByText('Ops chat')).toBeVisible()
  await page.getByRole('button', { name: 'Add webhook' }).click()
  await page.getByLabel('Name').fill('Pager')
  await page.getByLabel('Webhook URL').fill('https://pager.example/secret-token')
  await page.getByRole('checkbox', { name: 'Host down' }).check()
  await page.getByRole('button', { name: 'Save' }).click()
  await expect(page.getByRole('dialog')).toHaveCount(0)
  await expect(page.getByText('secret-token')).toHaveCount(0)

  await page.getByRole('button', { name: 'Send test to Ops chat' }).click()
  await expect(page.getByText('Test delivered.')).toBeVisible()
})

test('operator cannot reach the page', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', { body: user('OPERATOR') })
  await page.goto('/settings/notifications')
  await expect(page.getByText('You are not allowed to view this page.')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Add webhook' })).toHaveCount(0)
})
