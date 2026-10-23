import { expect, test } from './fixtures/api.ts'

const local = {
  id: 'a',
  name: 'Local',
  connectionType: 'LOCAL',
  endpoint: 'unix:///var/run/docker.sock',
  status: 'UP',
  error: null,
  dockerVersion: '27.1.2',
  cpus: 8,
  memoryBytes: 16 * 1024 ** 3,
  containers: { running: 1, stopped: 1, healthy: 0, unhealthy: 0 },
  images: 1,
  volumes: 1,
}
const as = (role: string) => ({ body: { username: 'u', role, setupRequired: false } })
const base = { image: 'x', status: 'Up', health: null, created: '2026-10-01T10:00:00Z', ports: [], composeService: null }
const containers = [
  { ...base, id: 'c1', name: 'web', state: 'running', composeProject: 'shop' },
  { ...base, id: 'c2', name: 'old', state: 'exited', composeProject: null },
]
const images = [{ id: 'sha256:aaaa', repoTags: ['nginx:1'], size: 1024, created: '2026-10-01T10:00:00Z', inUse: false }]
const volumes = [{ name: 'pgdata', driver: 'local', mountpoint: '/v/pgdata', created: null, inUse: false }]
const networks = [{ id: 'n1', name: 'backend', driver: 'bridge', scope: 'local', subnet: null, internal: false, containerCount: 0 }]

test.beforeEach(({ api }) => {
  api.mock('GET', '/api/hosts/overview', { body: [local] })
})

test('operator deletes a stopped container', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', as('OPERATOR'))
  api.mock('GET', '/api/hosts/a/containers', { body: containers })
  api.mock('GET', '/api/hosts/a/deployments', { body: [{ name: 'shop', containerCount: 1, runningCount: 1, managedDeploymentId: 'd1' }] })
  api.mock('DELETE', '/api/hosts/a/containers/c2', { status: 204 })
  await page.goto('/environments/a/containers')
  await page.getByRole('button', { name: 'Delete container old' }).click()
  const dialog = page.getByRole('dialog', { name: 'Delete container' })
  await expect(dialog.getByLabel(/Force/)).toHaveCount(0)
  const request = page.waitForRequest((r) => r.method() === 'DELETE' && r.url().endsWith('/containers/c2?force=false'))
  await dialog.getByRole('button', { name: 'Delete' }).click()
  await request
  await expect(dialog).toBeHidden()
})

test('running managed container: warning and force', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', as('ADMIN'))
  api.mock('GET', '/api/hosts/a/containers', { body: containers })
  api.mock('GET', '/api/hosts/a/deployments', { body: [{ name: 'shop', containerCount: 1, runningCount: 1, managedDeploymentId: 'd1' }] })
  api.mock('DELETE', '/api/hosts/a/containers/c1', { status: 204 })
  await page.goto('/environments/a/containers')
  await page.getByRole('button', { name: 'Delete container web' }).click()
  const dialog = page.getByRole('dialog')
  await expect(dialog.getByRole('note')).toContainText('Belongs to deployment shop')
  await dialog.getByLabel('Force (stop and remove)').check()
  const request = page.waitForRequest((r) => r.method() === 'DELETE' && r.url().endsWith('/containers/c1?force=true'))
  await dialog.getByRole('button', { name: 'Delete' }).click()
  await request
})

test('deletes an image, a volume and a network', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', as('ADMIN'))
  api.mock('GET', '/api/hosts/a/images', { body: images })
  api.mock('GET', '/api/hosts/a/volumes', { body: volumes })
  api.mock('GET', '/api/hosts/a/networks', { body: networks })
  api.mock('DELETE', '/api/hosts/a/images/sha256%3Aaaaa', { status: 204 })
  api.mock('DELETE', '/api/hosts/a/volumes/pgdata', { status: 204 })
  api.mock('DELETE', '/api/hosts/a/networks/n1', { status: 204 })
  for (const [path, label] of [
    ['images', 'Delete image nginx:1'],
    ['volumes', 'Delete volume pgdata'],
    ['networks', 'Delete network backend'],
  ]) {
    await page.goto(`/environments/a/${path}`)
    await page.getByRole('button', { name: label }).click()
    const request = page.waitForRequest((r) => r.method() === 'DELETE')
    await page.getByRole('dialog').getByRole('button', { name: 'Delete' }).click()
    await request
    await expect(page.getByRole('dialog')).toBeHidden()
  }
})

test('in-use refusal stays inline', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', as('ADMIN'))
  api.mock('GET', '/api/hosts/a/volumes', { body: volumes })
  api.mock('DELETE', '/api/hosts/a/volumes/pgdata', { status: 409, body: { code: 'RESOURCE_IN_USE', message: 'Volume is used by container db.' } })
  await page.goto('/environments/a/volumes')
  await page.getByRole('button', { name: 'Delete volume pgdata' }).click()
  await page.getByRole('dialog').getByRole('button', { name: 'Delete' }).click()
  await expect(page.getByRole('dialog').getByRole('alert')).toHaveText('Volume is used by container db.')
})

test('viewer sees no delete action', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', as('VIEWER'))
  api.mock('GET', '/api/hosts/a/images', { body: images })
  await page.goto('/environments/a/images')
  await expect(page.getByRole('table', { name: 'images' })).toBeVisible()
  await expect(page.getByRole('button', { name: /Delete/ })).toHaveCount(0)
})
