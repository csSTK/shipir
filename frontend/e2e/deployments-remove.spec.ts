import { expect, test } from './fixtures/api.ts'

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
  containers: { running: 2, stopped: 0, healthy: 0, unhealthy: 0 },
  images: 0,
  volumes: 0,
}
const live = [
  { name: 'shop', containerCount: 1, runningCount: 1, status: 'RUNNING', workingDir: '/srv/shop', managed: true, managedDeploymentId: 'm1' },
  { name: 'blog', containerCount: 1, runningCount: 1, status: 'RUNNING', workingDir: '/srv/blog', managed: false, managedDeploymentId: null },
]
const managed = [{ id: 'm1', name: 'shop', status: 'RUNNING', source: 'FILE', lastDeployedAt: null, createdAt: '2026-10-01T10:00:00Z' }]

function mockCommon(api: import('./fixtures/api.ts').ApiMock, role: string) {
  api.mock('GET', '/api/auth/me', { body: { username: 'u', role, setupRequired: false } })
  api.mock('GET', '/api/hosts/overview', { body: [host] })
  api.mock('GET', '/api/hosts/a/deployments', { body: live })
  api.mock('GET', '/api/hosts/a/managed-deployments', { body: managed })
}

test('operator removes an unmanaged deployment and keeps its volumes by default', async ({ page, api }) => {
  mockCommon(api, 'OPERATOR')
  api.mock('DELETE', '/api/hosts/a/deployments/blog', { status: 204 })
  await page.goto('/environments/a/deployments')
  await expect(page.getByRole('table', { name: 'deployments' })).toContainText('blog')
  await expect(page.getByRole('button', { name: 'Remove shop' })).toHaveCount(0)

  await page.getByRole('button', { name: 'Remove blog' }).click()
  const dialog = page.getByRole('dialog', { name: 'Remove deployment' })
  await expect(dialog).toContainText('docker compose down')
  await expect(dialog).toContainText('Its volumes are kept.')
  const request = page.waitForRequest((r) => r.method() === 'DELETE' && r.url().includes('/api/hosts/a/deployments/blog'))
  await dialog.getByRole('button', { name: 'Remove' }).click()
  expect((await request).url()).toContain('removeVolumes=false')
  await expect(page.getByRole('status')).toHaveText('blog removed.')
  await expect(page.getByRole('dialog')).toHaveCount(0)
})

test('admin removes an unmanaged deployment together with its volumes', async ({ page, api }) => {
  mockCommon(api, 'ADMIN')
  api.mock('DELETE', '/api/hosts/a/deployments/blog', { status: 204 })
  await page.goto('/environments/a/deployments')
  await page.getByRole('button', { name: 'Remove blog' }).click()
  const dialog = page.getByRole('dialog', { name: 'Remove deployment' })
  await dialog.getByLabel('Also remove volumes').check()
  await expect(dialog).toContainText('Data in its volumes will be deleted.')
  const request = page.waitForRequest((r) => r.method() === 'DELETE' && r.url().includes('/api/hosts/a/deployments/blog'))
  await dialog.getByRole('button', { name: 'Remove' }).click()
  expect((await request).url()).toContain('removeVolumes=true')
})

test('a failed compose down shows the error inside the dialog', async ({ page, api }) => {
  mockCommon(api, 'OPERATOR')
  api.mock('DELETE', '/api/hosts/a/deployments/blog', {
    status: 502,
    body: { code: 'COMPOSE_DOWN_FAILED', message: 'failed', details: ['container is busy'] },
  })
  await page.goto('/environments/a/deployments')
  await page.getByRole('button', { name: 'Remove blog' }).click()
  const dialog = page.getByRole('dialog', { name: 'Remove deployment' })
  await dialog.getByRole('button', { name: 'Remove' }).click()
  await expect(dialog.getByRole('alert')).toContainText('Docker could not stop the deployment')
  await expect(dialog).toBeVisible()
})

test('viewer sees deployments without remove actions', async ({ page, api }) => {
  mockCommon(api, 'VIEWER')
  await page.goto('/environments/a/deployments')
  await expect(page.getByRole('table', { name: 'deployments' })).toContainText('blog')
  await expect(page.getByRole('button', { name: /^Remove / })).toHaveCount(0)
})
