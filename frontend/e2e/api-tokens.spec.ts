import { expect, test } from './fixtures/api.ts'

const SECRET = 'shp_abcd1234E2ESECRETVALUE'
const WEBHOOK_TOKEN = 'shw_E2EWEBHOOKSECRET'
const BASE = '/api/hosts/a/managed-deployments'

const host = {
  id: 'a',
  name: 'Local',
  connectionType: 'LOCAL',
  endpoint: 'unix:///var/run/docker.sock',
  status: 'UP',
  error: null,
  dockerVersion: '27.1.2',
  cpus: 8,
  memoryBytes: 16 * 1024 ** 3,
  containers: { running: 0, stopped: 0, healthy: 0, unhealthy: 0 },
  images: 0,
  volumes: 0,
}
const deployment = {
  id: 's1',
  name: 'shop',
  status: 'RUNNING',
  source: 'FILE',
  lastDeployedAt: '2026-10-02T10:00:00Z',
  createdAt: '2026-10-01T10:00:00Z',
  yaml: 'services:\n  web:\n    image: nginx',
  envKeys: [],
}

test('creates an API token, reveals it once, and revokes it', async ({ page, context, api }) => {
  await context.grantPermissions(['clipboard-read', 'clipboard-write'])
  api.mock('GET', '/api/auth/me', { body: { username: 'pat', role: 'VIEWER', setupRequired: false } })
  api.mock('GET', '/api/hosts/overview', { body: [] })
  api.mock('GET', '/api/notifications/preferences', { body: [] })
  api.mock('GET', '/api/account/tokens', {
    body: [{ id: 't1', name: 'CI', prefix: 'shp_wxyz', createdAt: '2026-10-01T10:00:00Z', expiresAt: '2027-10-01T10:00:00Z', lastUsedAt: null }],
  })
  api.mock('POST', '/api/account/tokens', {
    status: 201,
    body: { id: 't2', name: 'Script', prefix: 'shp_abcd', createdAt: '2026-10-25T10:00:00Z', expiresAt: '2027-10-25T10:00:00Z', token: SECRET },
  })
  api.mock('DELETE', '/api/account/tokens/t1', { status: 204 })
  await page.goto('/account')

  const section = page.getByRole('region', { name: 'API tokens' })
  await expect(section.getByText('CI')).toBeVisible()
  await expect(section.getByText('shp_wxyz…')).toBeVisible()
  await expect(section.getByText('Authorization: Bearer', { exact: false })).toBeVisible()

  await section.getByRole('button', { name: 'Create token' }).click()
  const create = page.getByRole('dialog')
  await create.getByLabel('Name').fill('Script')
  await create.getByLabel('Expires').selectOption('30')
  await create.getByRole('button', { name: 'Create' }).click()

  const reveal = page.getByRole('dialog')
  await expect(reveal.getByLabel('Token')).toHaveValue(SECRET)
  await expect(reveal.getByText(/shown only once/i)).toBeVisible()
  await reveal.getByRole('button', { name: 'Copy' }).click()
  await expect(reveal.getByRole('button', { name: 'Copied' })).toBeVisible()
  expect(await page.evaluate(() => navigator.clipboard.readText())).toBe(SECRET)
  await reveal.getByRole('button', { name: 'Done' }).click()
  await expect(page.getByRole('dialog')).toHaveCount(0)
  await expect(page.getByText(SECRET)).toHaveCount(0)

  await section.getByRole('button', { name: 'Revoke token CI' }).click()
  await page.getByRole('dialog').getByRole('button', { name: 'Revoke' }).click()
  await expect(page.getByRole('dialog')).toHaveCount(0)
})

test('enables, rotates and disables the redeploy webhook with a one-time URL', async ({ page, context, api }) => {
  await context.grantPermissions(['clipboard-read', 'clipboard-write'])
  api.mock('GET', '/api/auth/me', { body: { username: 'u', role: 'OPERATOR', setupRequired: false } })
  api.mock('GET', '/api/hosts/overview', { body: [host] })
  api.mock('GET', '/api/hosts/a/deployments', { body: [] })
  api.mock('GET', BASE, { body: [] })
  api.mock('GET', `${BASE}/s1`, { body: deployment })
  api.mock('GET', `${BASE}/s1/versions`, { body: [] })
  api.mock('GET', `${BASE}/s1/files`, { body: [] })
  api.mock('GET', `${BASE}/s1/webhook`, { body: { enabled: false, createdAt: null, rotatedAt: null } })
  const issued = { enabled: true, createdAt: '2026-10-25T10:00:00Z', rotatedAt: null, token: WEBHOOK_TOKEN, path: `/api/webhooks/deploy/${WEBHOOK_TOKEN}` }
  api.mock('PUT', `${BASE}/s1/webhook`, { body: issued })
  api.mock('POST', `${BASE}/s1/webhook/rotate`, { body: issued })
  api.mock('DELETE', `${BASE}/s1/webhook`, { status: 204 })
  await page.goto('/environments/a/deployments/s1')

  const card = page.getByRole('region', { name: 'Redeploy webhook' })
  await expect(card.getByText('Disabled')).toBeVisible()
  await expect(card.getByText(/runs as the system/i)).toBeVisible()
  // The server is enabled once the PUT returns, so the status refetch that follows reports it.
  api.mock('GET', `${BASE}/s1/webhook`, { body: { enabled: true, createdAt: issued.createdAt, rotatedAt: null } })
  await card.getByRole('button', { name: 'Enable webhook' }).click()

  const url = `http://localhost:${new URL(page.url()).port}/api/webhooks/deploy/${WEBHOOK_TOKEN}`
  const reveal = page.getByRole('dialog')
  await expect(reveal.getByLabel('Webhook URL')).toHaveValue(url)
  await expect(reveal.getByText(`curl -X POST ${url}`)).toBeVisible()
  await reveal.getByRole('button', { name: 'Copy' }).click()
  expect(await page.evaluate(() => navigator.clipboard.readText())).toBe(url)
  await reveal.getByRole('button', { name: 'Done' }).click()
  await expect(card.getByText('Enabled', { exact: true })).toBeVisible()
  await expect(page.getByText(WEBHOOK_TOKEN)).toHaveCount(0)

  await page.reload()
  await expect(card.getByText('Enabled', { exact: true })).toBeVisible()
  await expect(page.getByText(WEBHOOK_TOKEN)).toHaveCount(0)

  await card.getByRole('button', { name: 'Rotate' }).click()
  const confirm = page.getByRole('dialog')
  await expect(confirm.getByText(/old url stops working/i)).toBeVisible()
  await confirm.getByRole('button', { name: 'Rotate' }).click()
  await expect(page.getByRole('dialog').getByLabel('Webhook URL')).toHaveValue(url)
  await page.getByRole('dialog').getByRole('button', { name: 'Done' }).click()

  api.mock('GET', `${BASE}/s1/webhook`, { body: { enabled: false, createdAt: null, rotatedAt: null } })
  await card.getByRole('button', { name: 'Disable' }).click()
  await page.getByRole('dialog').getByRole('button', { name: 'Disable' }).click()
  await expect(card.getByText('Disabled')).toBeVisible()
})

test('viewers do not see the webhook card', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', { body: { username: 'v', role: 'VIEWER', setupRequired: false } })
  api.mock('GET', '/api/hosts/overview', { body: [host] })
  api.mock('GET', '/api/hosts/a/deployments', { body: [] })
  api.mock('GET', BASE, { body: [] })
  api.mock('GET', `${BASE}/s1`, { body: deployment })
  api.mock('GET', `${BASE}/s1/versions`, { body: [] })
  api.mock('GET', `${BASE}/s1/files`, { body: [] })
  await page.goto('/environments/a/deployments/s1')
  await expect(page.getByRole('heading', { name: 'shop' })).toBeVisible()
  await expect(page.getByRole('region', { name: 'Redeploy webhook' })).toHaveCount(0)
})
