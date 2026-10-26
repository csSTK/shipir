import { expect, test } from './fixtures/api.ts'

const BASE = '/api/hosts/a/managed-deployments'
const UPTIME = `${BASE}/s1/uptime-checks`

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
const check = {
  id: 'c1',
  name: 'Homepage',
  url: 'https://example.com/health?token=SECRET',
  expectedStatus: 200,
  intervalSeconds: 60,
  timeoutSeconds: 10,
  enabled: true,
  lastStatus: 'UP',
  lastCheckedAt: '2026-10-25T10:00:00Z',
  lastChangeAt: '2026-10-25T09:00:00Z',
  consecutiveFailures: 0,
  recentResults: [{ checkedAt: '2026-10-25T10:00:00Z', ok: true, statusCode: 200, latencyMs: 12, errorCode: null }],
}

function mockCommon(api: import('./fixtures/api.ts').ApiMock, role: string) {
  api.mock('GET', '/api/auth/me', { body: { username: 'u', role, setupRequired: false } })
  api.mock('GET', '/api/hosts/overview', { body: [host] })
  api.mock('GET', '/api/hosts/a/deployments', { body: [] })
  api.mock('GET', BASE, { body: [] })
  api.mock('GET', `${BASE}/s1`, { body: deployment })
  api.mock('GET', `${BASE}/s1/webhook`, { body: { enabled: false, createdAt: null, rotatedAt: null } })
  api.mock('GET', `${BASE}/s1/versions`, { body: [] })
  api.mock('GET', `${BASE}/s1/services`, { body: { services: ['web'], profiles: [] } })
  api.mock('GET', `${BASE}/s1/files`, { body: [] })
}

test('lists checks without query string, adds one, shows a server error, deletes', async ({ page, api }) => {
  mockCommon(api, 'OPERATOR')
  api.mock('GET', UPTIME, { body: [check] })
  await page.goto('/environments/a/deployments/s1')

  const card = page.getByRole('region', { name: 'Uptime checks' })
  await expect(card.getByText('Homepage')).toBeVisible()
  await expect(card.getByText('Up', { exact: true })).toBeVisible()
  await expect(card.getByText(/example\.com\/health · every 60 s/)).toBeVisible()
  await expect(card.getByText(/SECRET/)).toHaveCount(0)

  await card.getByRole('button', { name: 'Add check' }).click()
  const dialog = page.getByRole('dialog')
  await dialog.getByLabel('Name', { exact: true }).fill('LAN')
  await dialog.getByLabel('URL').fill('http://10.0.0.5/')
  await dialog.getByLabel('Interval (seconds)').fill('5')
  await dialog.getByRole('button', { name: 'Save' }).click()
  await expect(dialog.getByText(/between 30 and 86400/)).toBeVisible()
  await dialog.getByRole('button', { name: 'Cancel' }).click()

  api.mock('DELETE', `${UPTIME}/c1`, { status: 204 })
  api.mock('GET', UPTIME, { body: [] })
  await card.getByRole('button', { name: 'Delete Homepage' }).click()
  await page.getByRole('dialog').getByRole('button', { name: 'Delete' }).click()
  await expect(card.getByText('No uptime checks')).toBeVisible()
})

test('shows the allow-private-ranges hint next to an EGRESS_DENIED result', async ({ page, api }) => {
  mockCommon(api, 'OPERATOR')
  api.mock('GET', UPTIME, {
    body: [
      {
        ...check,
        lastStatus: 'DOWN',
        recentResults: [{ checkedAt: '2026-10-25T10:00:00Z', ok: false, statusCode: null, latencyMs: null, errorCode: 'EGRESS_DENIED' }],
      },
    ],
  })
  await page.goto('/environments/a/deployments/s1')
  await expect(page.getByRole('region', { name: 'Uptime checks' }).getByText(/shipir\.egress\.allow-private-ranges/)).toBeVisible()
})

test('viewers do not see the uptime card', async ({ page, api }) => {
  mockCommon(api, 'VIEWER')
  await page.goto('/environments/a/deployments/s1')
  await expect(page.getByRole('heading', { name: 'shop' })).toBeVisible()
  await expect(page.getByRole('region', { name: 'Uptime checks' })).toHaveCount(0)
})
