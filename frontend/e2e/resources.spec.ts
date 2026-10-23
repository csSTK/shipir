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
const c = { status: 'Up', health: null, created: '2026-10-01T10:00:00Z', ports: [], composeProject: null, composeService: null }
const containers = [
  { ...c, id: '1', name: 'web', image: 'nginx:1', state: 'running', health: 'healthy', ports: ['8080:80/tcp'], composeProject: 'shop' },
  { ...c, id: '2', name: 'api', image: 'app:2', state: 'exited', composeProject: 'shop' },
  { ...c, id: '3', name: 'cache', image: 'redis:7', state: 'running' },
]
const stacks = [
  { name: 'shop', containerCount: 2, runningCount: 1, status: 'PARTIAL', workingDir: '/srv/shop' },
  { name: 'blog', containerCount: 1, runningCount: 1, status: 'RUNNING', workingDir: null },
]

test.beforeEach(({ api }) => {
  api.mock('GET', '/api/auth/me', me)
  api.mock('GET', '/api/hosts/overview', { body: [local] })
  api.mock('GET', '/api/hosts/a/managed-stacks', { body: [] })
})

test('stacks: search, sort, click opens filtered containers', async ({ page, api }) => {
  api.mock('GET', '/api/hosts/a/stacks', { body: stacks })
  api.mock('GET', '/api/hosts/a/containers', { body: containers })
  await page.goto('/environments/a/stacks')
  const rows = page.getByRole('table', { name: 'stacks' }).getByRole('row')
  await expect(rows.nth(1)).toContainText('blog')
  await page.getByRole('button', { name: 'Name' }).click()
  await expect(rows.nth(1)).toContainText('shop')
  await page.getByRole('searchbox', { name: 'Search stacks' }).fill('blo')
  await expect(rows).toHaveCount(2)

  await page.getByRole('searchbox', { name: 'Search stacks' }).fill('')
  await page.getByRole('link', { name: 'shop' }).click()
  await expect(page).toHaveURL(/\/environments\/a\/containers\?stack=shop$/)
  const body = page.getByRole('table', { name: 'containers' })
  await expect(body.getByRole('row')).toHaveCount(3)
  await expect(body).not.toContainText('cache')
  await page.getByRole('button', { name: /Stack: shop/ }).click()
  await expect(body.getByRole('row')).toHaveCount(4)
})

test('containers: search and sort', async ({ page, api }) => {
  api.mock('GET', '/api/hosts/a/containers', { body: containers })
  await page.goto('/environments/a/containers')
  const table = page.getByRole('table', { name: 'containers' })
  await expect(table.getByRole('row').nth(1)).toContainText('api')
  await expect(table).toContainText('8080:80/tcp')
  await page.getByRole('button', { name: 'Name' }).click()
  await expect(table.getByRole('row').nth(1)).toContainText('web')
  await page.getByRole('searchbox', { name: 'Search containers' }).fill('redis')
  await expect(table.getByRole('row')).toHaveCount(2)
})

test('containers: unreachable host shows the error', async ({ page, api }) => {
  api.mock('GET', '/api/hosts/a/containers', { status: 502, body: { code: 'UNREACHABLE', message: 'x' } })
  await page.goto('/environments/a/containers')
  await expect(page.getByRole('alert')).toHaveText('Could not reach the Docker host.')
})

test('stacks: timeout shows the error, empty list shows empty state', async ({ page, api }) => {
  api.mock('GET', '/api/hosts/a/stacks', { status: 502, body: { code: 'TIMEOUT', message: 'x' } })
  await page.goto('/environments/a/stacks')
  await expect(page.getByRole('alert')).toHaveText('The connection timed out.')

  api.mock('GET', '/api/hosts/a/stacks', { body: [] })
  await page.getByRole('button', { name: 'Retry' }).click()
  await expect(page.getByText('No stacks in this environment.')).toBeVisible()
})
