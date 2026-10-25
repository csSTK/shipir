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
  containers: { running: 1, stopped: 2, healthy: 0, unhealthy: 0 },
  images: 5,
  volumes: 1,
}
const group = (count: number, sizeBytes = 0) => ({ count, sizeBytes, items: [] })
const preview = { containers: group(2), images: group(3, 3 * 1024 ** 2), volumes: group(1), networks: group(1), managedVolumesExcluded: 0 }
const counts = (removed: number) => ({ removed, skipped: 0, reclaimedBytes: 0 })
const result = {
  containers: counts(2),
  images: { removed: 3, skipped: 0, reclaimedBytes: 3 * 1024 ** 2 },
  volumes: counts(0),
  networks: counts(1),
  reclaimedBytes: 3 * 1024 ** 2,
}
const DISK_USAGE = {
  images: { count: 12, totalSize: 1024 ** 3, reclaimableSize: 0 },
  containers: { count: 3, totalSize: 0, reclaimableSize: 0 },
  volumes: { count: 4, totalSize: 0, reclaimableSize: 0 },
  buildCache: { count: 0, totalSize: 0, reclaimableSize: 0 },
}
const as = (role: string) => ({ body: { username: 'u', role, setupRequired: false } })

test.beforeEach(({ api }) => {
  api.mock('GET', '/api/hosts/overview', { body: [local] })
  api.mock('GET', '/api/hosts/a/disk-usage', { body: DISK_USAGE })
})

test('operator previews and cleans up', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', as('OPERATOR'))
  api.mock('GET', '/api/hosts/a/prune/preview', { body: preview })
  api.mock('POST', '/api/hosts/a/prune', { body: result })
  await page.goto('/environments/a')
  await page.getByRole('button', { name: 'Clean up' }).click()
  const dialog = page.getByRole('dialog', { name: 'Clean up unused resources' })
  await expect(dialog.getByLabel('Preview')).toContainText('3 images (3 MiB)')
  const request = page.waitForRequest((r) => r.method() === 'POST' && r.url().endsWith('/hosts/a/prune'))
  await dialog.getByRole('button', { name: 'Clean up' }).click()
  expect((await request).postDataJSON()).toMatchObject({ containers: true, images: 'DANGLING', volumes: false, networks: true })
  await expect(dialog.getByRole('status')).toContainText('3 images removed')
})

test('forbidden answer shows inline', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', as('OPERATOR'))
  api.mock('GET', '/api/hosts/a/prune/preview', { body: preview })
  api.mock('POST', '/api/hosts/a/prune', { status: 403, body: { code: 'FORBIDDEN', message: 'no' } })
  await page.goto('/environments/a')
  await page.getByRole('button', { name: 'Clean up' }).click()
  const dialog = page.getByRole('dialog')
  await dialog.getByRole('button', { name: 'Clean up' }).click()
  await expect(dialog.getByRole('alert')).toContainText('not allowed')
})

test('viewer has no Clean up button', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', as('VIEWER'))
  await page.goto('/environments/a')
  await expect(page.getByRole('heading', { name: 'Local' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Clean up' })).toHaveCount(0)
})
