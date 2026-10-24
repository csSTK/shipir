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
  containers: { running: 1, stopped: 0, healthy: 0, unhealthy: 0 },
  images: 0,
  volumes: 0,
}
const listed = {
  id: 'c1',
  name: 'web',
  image: 'nginx:1',
  state: 'running',
  status: 'Up',
  health: null,
  created: '2026-10-01T10:00:00Z',
  ports: [],
  composeProject: null,
  composeService: null,
}
const details = {
  id: 'c1',
  name: 'web',
  image: 'nginx:1',
  state: 'running',
  created: '2026-10-01T10:00:00Z',
  startedAt: '2026-10-01T10:00:05Z',
  restartCount: 0,
  restartPolicy: { name: 'always', maxRetries: 0 },
  command: ['nginx'],
  env: [{ key: 'DB_PASSWORD', value: 's3cret' }],
  mounts: [{ type: 'volume', name: 'data', source: '/var/lib/docker/volumes/data', destination: '/data', readOnly: false }],
  labels: { app: 'shop' },
  networks: [{ name: 'bridge', ipAddress: '172.17.0.2', gateway: '172.17.0.1', macAddress: '02:42:ac', aliases: [] }],
  ports: [{ containerPort: 80, protocol: 'tcp', hostIp: '0.0.0.0', hostPort: 8080 }],
  health: { status: 'healthy', failingStreak: 0, recentLogs: [] },
  composeProject: null,
  composeService: null,
}

test.beforeEach(({ api }) => {
  api.mock('GET', '/api/auth/me', { body: { username: 'u', role: 'OPERATOR', setupRequired: false } })
  api.mock('GET', '/api/hosts/overview', { body: [host] })
  api.mock('GET', '/api/hosts/a/containers', { body: [listed] })
  api.mock('GET', '/api/hosts/a/images', { body: [] })
  api.mock('GET', '/api/hosts/a/deployments', { body: [] })
})

test('opens details from the container name, masks env until revealed, links to logs', async ({ page, api }) => {
  api.mock('GET', '/api/hosts/a/containers/c1', { body: details })
  await page.goto('/environments/a/containers')
  await page.getByRole('link', { name: 'Details of web' }).click()
  await expect(page).toHaveURL(/\/environments\/a\/containers\/c1$/)
  await expect(page.getByRole('heading', { name: 'web' })).toBeVisible()
  await expect(page.getByText('8080 → 80/tcp')).toBeVisible()
  await expect(page.getByText('172.17.0.2')).toBeVisible()
  await expect(page.getByText('DB_PASSWORD')).toBeVisible()
  await expect(page.getByText('s3cret')).toHaveCount(0)
  await page.getByRole('button', { name: 'Reveal values' }).click()
  await expect(page.getByText('s3cret')).toBeVisible()
  await expect(page.getByRole('link', { name: 'Logs' })).toHaveAttribute('href', '/environments/a/containers/c1/logs')
})

test('shows a not-found message for a removed container', async ({ page, api }) => {
  api.mock('GET', '/api/hosts/a/containers/c1', { status: 404, body: { code: 'RESOURCE_NOT_FOUND', message: 'gone' } })
  await page.goto('/environments/a/containers/c1')
  await expect(page.getByRole('alert')).toContainText('This container no longer exists.')
})
