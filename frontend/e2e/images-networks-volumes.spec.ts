import { expect, test } from './fixtures/api.ts'

const me = { body: { username: 'u', role: 'VIEWER', setupRequired: false } }
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
  containers: { running: 2, stopped: 1, healthy: 1, unhealthy: 0 },
  images: 3,
  volumes: 1,
}
const images = [
  { id: 'sha256:aaaaaaaaaaaaaaaa', repoTags: ['nginx:1'], size: 5 * 1024 ** 2, created: '2026-10-01T10:00:00Z', inUse: true },
  { id: 'sha256:bbbbbbbbbbbbbbbb', repoTags: ['redis:7'], size: 2 * 1024 ** 3, created: '2026-09-01T10:00:00Z', inUse: false },
  { id: 'sha256:cccccccccccccccc', repoTags: [], size: 1024, created: '2026-08-01T10:00:00Z', inUse: false },
]
const networks = [
  { id: '1', name: 'bridge', driver: 'bridge', scope: 'local', subnet: '172.17.0.0/16', internal: false, containerCount: 2 },
  { id: '2', name: 'backend', driver: 'bridge', scope: 'local', subnet: null, internal: true, containerCount: 0 },
]
const volumes = [
  { name: 'pgdata', driver: 'local', mountpoint: '/v/pgdata', created: null, inUse: true },
  { name: 'cache', driver: 'local', mountpoint: '/v/cache', created: null, inUse: false },
]

test.beforeEach(({ api }) => {
  api.mock('GET', '/api/auth/me', me)
  api.mock('GET', '/api/hosts/overview', { body: [local] })
})

test('images: unused marked, search and sort', async ({ page, api }) => {
  api.mock('GET', '/api/hosts/a/images', { body: images })
  await page.goto('/environments/a/images')
  const table = page.getByRole('table', { name: 'images' })
  await expect(table).toContainText('<none>')
  await expect(table.getByText('Unused')).toHaveCount(2)
  await expect(table.getByText('In use')).toHaveCount(1)
  await page.getByRole('button', { name: 'Size' }).click()
  await page.getByRole('button', { name: 'Size' }).click()
  await expect(table.getByRole('row').nth(1)).toContainText('redis:7')
  await page.getByRole('searchbox', { name: 'Search images' }).fill('nginx')
  await expect(table.getByRole('row')).toHaveCount(2)
})

test('networks: internal marked, search and sort', async ({ page, api }) => {
  api.mock('GET', '/api/hosts/a/networks', { body: networks })
  await page.goto('/environments/a/networks')
  const table = page.getByRole('table', { name: 'networks' })
  await expect(table.getByRole('row').nth(1)).toContainText('backend')
  await expect(table.getByText('Internal')).toBeVisible()
  await page.getByRole('button', { name: 'Name' }).click()
  await expect(table.getByRole('row').nth(1)).toContainText('bridge')
  await page.getByRole('searchbox', { name: 'Search networks' }).fill('172.17')
  await expect(table.getByRole('row')).toHaveCount(2)
})

test('volumes: missing created date renders a dash, unused marked', async ({ page, api }) => {
  api.mock('GET', '/api/hosts/a/volumes', { body: volumes })
  await page.goto('/environments/a/volumes')
  const table = page.getByRole('table', { name: 'volumes' })
  await expect(table.getByRole('row').nth(1)).toContainText('cache')
  await expect(table.getByRole('row').nth(1)).toContainText('–')
  await expect(table.getByText('Unused')).toHaveCount(1)
  await page.getByRole('searchbox', { name: 'Search volumes' }).fill('pg')
  await expect(table.getByRole('row')).toHaveCount(2)
})

test('images: unreachable host shows the error, retry shows the empty state', async ({ page, api }) => {
  api.mock('GET', '/api/hosts/a/images', { status: 502, body: { code: 'UNREACHABLE', message: 'x' } })
  await page.goto('/environments/a/images')
  await expect(page.getByRole('alert')).toHaveText('Could not reach the Docker host. Check the address, port, TLS setup and firewall.')
  api.mock('GET', '/api/hosts/a/images', { body: [] })
  await page.getByRole('button', { name: 'Retry' }).click()
  await expect(page.getByText('No images in this environment.')).toBeVisible()
})

// Layout (shipr-ycvc.98): at phone width the page header actions get their own row under the title, not a cramped shared line.
test('images header: at 375px the actions sit below the title, inside the viewport', async ({ page, api }) => {
  await page.setViewportSize({ width: 375, height: 800 })
  api.mock('GET', '/api/auth/me', { body: { username: 'u', role: 'ADMIN', setupRequired: false } })
  api.mock('GET', '/api/hosts/a/images', { body: images })
  await page.goto('/environments/a/images')
  const title = await page.getByRole('heading', { name: 'Images' }).boundingBox()
  const pull = await page.getByRole('button', { name: 'Pull image' }).boundingBox()
  const build = await page.getByRole('button', { name: 'Build image' }).boundingBox()
  if (!title || !pull || !build) throw new Error('header not rendered')
  expect(Math.min(pull.y, build.y)).toBeGreaterThanOrEqual(title.y + title.height)
  expect(Math.max(pull.x + pull.width, build.x + build.width)).toBeLessThanOrEqual(375)
})
