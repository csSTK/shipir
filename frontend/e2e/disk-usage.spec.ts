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
const usage = {
  images: { count: 12, totalSize: 1.2 * 1024 ** 3, reclaimableSize: 800 * 1024 ** 2 },
  containers: { count: 3, totalSize: 300 * 1024 ** 2, reclaimableSize: 0 },
  volumes: { count: 4, totalSize: 2 * 1024 ** 3, reclaimableSize: 512 * 1024 ** 2 },
  buildCache: { count: null, totalSize: null, reclaimableSize: null },
  largest: [
    { category: 'VOLUME', name: 'pgdata', size: 2 * 1024 ** 3 },
    { category: 'IMAGE', name: 'postgres:16', size: 600 * 1024 ** 2 },
  ],
}
const as = (role: string) => ({ body: { username: 'u', role, setupRequired: false } })

test.beforeEach(({ api }) => {
  api.mock('GET', '/api/hosts/overview', { body: [local] })
  api.mock('GET', '/api/hosts/a/deployments', { body: [] })
  api.mock('GET', '/api/hosts/a/managed-deployments', { body: [] })
  api.mock('GET', '/api/hosts/a/networks', { body: [] })
})

test('shows the breakdown with reclaimable amounts and unknown values', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', as('VIEWER'))
  api.mock('GET', '/api/hosts/a/disk-usage', { body: usage })
  await page.goto('/environments/a')
  await expect(page.getByRole('heading', { name: 'Disk usage' })).toBeVisible()
  const bar = page.getByRole('img', { name: /Disk usage: Images 1\.2 GiB/ })
  await expect(bar).toHaveAccessibleName(/Build cache unknown/)
  await expect(page.getByText('Reclaimable 800 MiB')).toBeVisible()
  await expect(page.getByText('Reclaimable unknown')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Review cleanup' })).toHaveCount(0)
})

test('lists the largest items and an empty state', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', as('VIEWER'))
  api.mock('GET', '/api/hosts/a/disk-usage', { body: usage })
  await page.goto('/environments/a')
  const list = page.getByRole('list', { name: 'Largest items' })
  await expect(list.getByRole('listitem')).toHaveCount(2)
  await expect(list.getByRole('listitem').first()).toContainText('pgdata')
  await expect(list.getByRole('listitem').first()).toContainText('Volume')
  await expect(list.getByRole('listitem').first()).toContainText('2 GiB')
  api.mock('GET', '/api/hosts/a/disk-usage', { body: { ...usage, largest: [] } })
  await page.getByRole('button', { name: 'Refresh disk usage' }).click()
  await expect(page.getByText('No items to show.')).toBeVisible()
})

test('deployment-only access explains the restriction', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', as('VIEWER'))
  api.mock('GET', '/api/hosts/a/disk-usage', { status: 403, body: { code: 'FORBIDDEN', message: 'no' } })
  await page.goto('/environments/a')
  await expect(page.getByText('Only available with access to the whole environment.')).toBeVisible()
  await expect(page.getByRole('alert')).toHaveCount(0)
})

test('a failed load offers retry', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', as('VIEWER'))
  api.mock('GET', '/api/hosts/a/disk-usage', { status: 502, body: { code: 'UNREACHABLE', message: 'docker' } })
  await page.goto('/environments/a')
  await expect(page.getByRole('alert')).toContainText('Could not load disk usage.')
  api.mock('GET', '/api/hosts/a/disk-usage', { body: usage })
  await page.getByRole('button', { name: 'Retry' }).click()
  await expect(page.getByRole('img', { name: /Disk usage/ })).toBeVisible()
})

test('refresh requests the numbers again', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', as('VIEWER'))
  api.mock('GET', '/api/hosts/a/disk-usage', { body: usage })
  await page.goto('/environments/a')
  await expect(page.getByRole('img', { name: /Disk usage/ })).toBeVisible()
  const request = page.waitForRequest((r) => r.method() === 'GET' && r.url().endsWith('/hosts/a/disk-usage'))
  await page.getByRole('button', { name: 'Refresh disk usage' }).click()
  await request
})

test('operator opens the prune dialog from the card', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', as('OPERATOR'))
  api.mock('GET', '/api/hosts/a/disk-usage', { body: usage })
  api.mock('GET', '/api/hosts/a/prune/preview', {
    body: {
      containers: { count: 0, sizeBytes: 0, items: [] },
      images: { count: 0, sizeBytes: 0, items: [] },
      volumes: { count: 0, sizeBytes: 0, items: [] },
      networks: { count: 0, sizeBytes: 0, items: [] },
      managedVolumesExcluded: 0,
    },
  })
  await page.goto('/environments/a')
  await page.getByRole('button', { name: 'Review cleanup' }).click()
  await expect(page.getByRole('dialog', { name: 'Clean up unused resources' })).toBeVisible()
})
