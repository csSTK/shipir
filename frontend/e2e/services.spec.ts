import { expect, mockDeploymentCards, test } from './fixtures/api.ts'

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
  profiles: ['debug'],
}

function mockCommon(api: import('./fixtures/api.ts').ApiMock, role: string) {
  api.mock('GET', '/api/auth/me', { body: { username: 'u', role, setupRequired: false } })
  api.mock('GET', '/api/hosts/overview', { body: [host] })
  api.mock('GET', '/api/hosts/a/deployments', { body: [] })
  api.mock('GET', BASE, { body: [] })
  api.mock('GET', `${BASE}/s1`, { body: deployment })
  mockDeploymentCards(api, `${BASE}/s1`)
}

test('restarts a service, handles busy, and saves profiles', async ({ page, api }) => {
  mockCommon(api, 'OPERATOR')
  api.mock('GET', `${BASE}/s1/services`, { body: { services: ['db', 'web'], profiles: ['debug', 'tools'] } })
  await page.goto('/environments/a/deployments/s1')

  const card = page.getByRole('region', { name: 'Services' })
  await expect(card.getByText('web', { exact: true })).toBeVisible()

  api.mock('POST', `${BASE}/s1/services/web/restart`, { status: 409, body: { code: 'DEPLOYMENT_IN_PROGRESS', message: 'busy' } })
  await card.getByRole('button', { name: 'Restart web' }).click()
  await expect(card.getByText(/already in progress/i)).toBeVisible()

  api.mock('POST', `${BASE}/s1/services/web/restart`, { status: 202, body: { runId: 'run-1', versionId: 'v1' } })
  await card.getByRole('button', { name: 'Restart web' }).click()
  await expect(page.getByText(/already in progress/i)).toHaveCount(0)
  await expect(page.getByLabel('Deployment output')).toBeVisible()

  await expect(card.getByRole('checkbox', { name: 'debug' })).toBeChecked()
  await card.getByRole('checkbox', { name: 'tools' }).check()
  // The save invalidates the deployment query; the refetch must return the saved profiles, or the picker resets to the stale ones and
  // "Save profiles" only looks disabled while the request is still pending (the old flake).
  const saved = { ...deployment, profiles: ['debug', 'tools'] }
  api.mock('PUT', `${BASE}/s1/profiles`, { body: saved })
  api.mock('GET', `${BASE}/s1`, { body: saved })
  const put = page.waitForRequest((r) => r.method() === 'PUT' && r.url().endsWith(`${BASE}/s1/profiles`))
  await card.getByRole('button', { name: 'Save profiles' }).click()
  expect((await put).postDataJSON()).toEqual({ profiles: ['debug', 'tools'] })
  await expect(card.getByRole('button', { name: 'Save profiles' })).toBeDisabled()
})

test('viewers do not see the services card', async ({ page, api }) => {
  mockCommon(api, 'VIEWER')
  await page.goto('/environments/a/deployments/s1')
  await expect(page.getByRole('heading', { name: 'shop' })).toBeVisible()
  await expect(page.getByRole('region', { name: 'Services' })).toHaveCount(0)
})
