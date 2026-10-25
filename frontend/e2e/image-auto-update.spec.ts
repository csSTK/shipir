import { expect, test, type ApiMock } from './fixtures/api.ts'

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
const BASE = '/api/hosts/a/managed-deployments'
const imageAutoUpdate = { enabled: false, windowStart: null, windowEnd: null, lastAttemptAt: '2026-10-02T03:00:00Z', lastResult: 'APPLIED' }
const deployment = {
  id: 's1',
  name: 'shop',
  status: 'RUNNING',
  source: 'FILE',
  lastDeployedAt: '2026-10-02T10:00:00Z',
  createdAt: '2026-10-01T10:00:00Z',
  yaml: 'services:\n  web:\n    image: nginx',
  envKeys: [],
  git: null,
  imageAutoUpdate,
}
const versions = [
  { id: 'v1', source: 'FILE', commitSha: null, createdBy: 'u', createdAt: '2026-10-01T10:00:00Z', current: true, deployed: true },
]

function mockCommon(api: ApiMock, role: string, detail: unknown = deployment) {
  api.mock('GET', '/api/auth/me', { body: { username: 'u', role, setupRequired: false } })
  api.mock('GET', '/api/hosts/overview', { body: [host] })
  api.mock('GET', '/api/hosts/a/deployments', { body: [] })
  api.mock('GET', BASE, { body: [] })
  api.mock('GET', `${BASE}/s1`, { body: detail })
  api.mock('GET', `${BASE}/s1/webhook`, { body: { enabled: false, createdAt: null, rotatedAt: null } })
  api.mock('GET', `${BASE}/s1/versions`, { body: versions })
  api.mock('GET', `${BASE}/s1/files`, { body: [] })
}

test('operator enables image updates with a UTC window', async ({ page, api }) => {
  mockCommon(api, 'OPERATOR')
  api.mock('PUT', `${BASE}/s1/image-auto-update`, { body: deployment })
  await page.goto('/environments/a/deployments/s1')
  const panel = page.getByRole('region', { name: 'Automatic image updates' })
  await expect(panel).toContainText('Applied')
  await expect(panel).toContainText('UTC')
  const request = page.waitForRequest((r) => r.method() === 'PUT' && r.url().endsWith('/image-auto-update'))
  await panel.getByRole('switch', { name: 'Automatic image updates' }).check()
  await panel.getByLabel('Window start (UTC)').fill('02:00')
  await panel.getByLabel('Window end (UTC)').fill('05:30')
  await panel.getByRole('button', { name: 'Save' }).click()
  expect((await request).postDataJSON()).toEqual({ enabled: true, windowStart: '02:00', windowEnd: '05:30' })
  await expect(panel.getByRole('status')).toContainText('Saved')
})

test('a half-filled window is rejected inline', async ({ page, api }) => {
  mockCommon(api, 'OPERATOR')
  await page.goto('/environments/a/deployments/s1')
  const panel = page.getByRole('region', { name: 'Automatic image updates' })
  await panel.getByLabel('Window start (UTC)').fill('02:00')
  await panel.getByRole('button', { name: 'Save' }).click()
  await expect(panel.getByRole('alert')).toContainText('both')
})

test('viewers see the settings read-only', async ({ page, api }) => {
  mockCommon(api, 'VIEWER')
  await page.goto('/environments/a/deployments/s1')
  const panel = page.getByRole('region', { name: 'Automatic image updates' })
  await expect(panel.getByRole('switch', { name: 'Automatic image updates' })).toBeDisabled()
  await expect(panel.getByRole('button', { name: 'Save' })).toHaveCount(0)
})

test('Git deployments do not show the card', async ({ page, api }) => {
  mockCommon(api, 'OPERATOR', { ...deployment, source: 'GIT', imageAutoUpdate: null, git: {
    repoUrl: 'https://github.com/acme/shop.git', ref: 'main', composePath: 'compose.yaml', credentialId: null,
    lastCommitSha: null, autoUpdateEnabled: false, autoUpdateIntervalSeconds: 300, lastPolledAt: null, lastPollError: null,
  } })
  await page.goto('/environments/a/deployments/s1')
  await expect(page.getByRole('region', { name: 'Git source' })).toBeVisible()
  await expect(page.getByRole('region', { name: 'Automatic image updates' })).toHaveCount(0)
})
