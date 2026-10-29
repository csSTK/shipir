import { expect, mockDeploymentCards, test } from './fixtures/api.ts'
import { mockBroker } from './fixtures/stomp.ts'

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
const BASE = '/api/hosts/a/managed-deployments'
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
const container = (id: string, name: string, service: string) => ({
  id,
  name,
  image: 'x:1',
  state: 'running',
  status: 'Up',
  health: null,
  created: '2026-10-01T10:00:00Z',
  ports: [],
  composeProject: 'shop',
  composeService: service,
})

test.beforeEach(({ api }) => {
  api.mock('GET', '/api/auth/me', { body: { username: 'u', role: 'VIEWER', setupRequired: false } })
  api.mock('GET', '/api/hosts/overview', { body: [host] })
  api.mock('GET', '/api/hosts/a/deployments', { body: [] })
  api.mock('GET', '/api/hosts/a/containers', { body: [container('c1', 'shop-web-1', 'web'), container('c2', 'shop-db-1', 'db')] })
  api.mock('GET', BASE, { body: [] })
  api.mock('GET', `${BASE}/s1`, { body: deployment })
  mockDeploymentCards(api, `${BASE}/s1`)
})

const web = (seq: number, line: string, stream = 'STDOUT') => ({ seq, type: 'LINE', service: 'web', container: 'shop-web-1', containerId: 'c1', stream, line })
const db = (seq: number, line: string) => ({ seq, type: 'LINE', service: 'db', container: 'shop-db-1', containerId: 'c2', stream: 'STDOUT', line })

test('shows the merged stream, toggles a service and downloads one container', async ({ page, api }) => {
  api.mock('GET', '/api/hosts/a/containers/c2/logs/download', { body: null })
  const subs = await mockBroker(page, (send) => {
    send(web(1, 'GET / 200'))
    send(db(2, 'ready to accept connections'))
    send(web(3, 'upstream timed out', 'STDERR'))
  })
  await page.goto('/environments/a/deployments/s1')
  await page.getByRole('button', { name: 'Show logs' }).click()
  const log = page.getByRole('log', { name: 'Deployment log' })
  await expect(log).toContainText('GET / 200')
  await expect(log).toContainText('ready to accept connections')
  expect(subs[0].destination).toBe('/topic/hosts/a/deployments/shop/logs')
  expect(subs[0].tail).toBe('100')

  await page.getByRole('button', { name: 'web' }).click()
  await expect(log).not.toContainText('GET / 200')
  await expect(log).toContainText('ready to accept connections')
  await page.getByRole('button', { name: 'web' }).click()
  await expect(log).toContainText('GET / 200')

  await page.getByRole('searchbox', { name: 'Search logs' }).fill('timed')
  await expect(log.locator('mark')).toHaveCount(1)

  await page.getByLabel('Download container').selectOption('c2')
  const [request] = await Promise.all([
    page.waitForRequest((r) => r.url().includes('/api/hosts/a/containers/c2/logs/download?tail=100&timestamps=false')),
    page.waitForEvent('download'),
    page.getByRole('button', { name: /Download/ }).click(),
  ])
  expect(request.method()).toBe('GET')
})

test('shows project-level banners without ending the stream', async ({ page }) => {
  await mockBroker(page, (send) => {
    send(web(1, 'first'))
    send({ seq: 2, type: 'STATUS', status: 'ERROR', line: 'CONTAINER_LIMIT' })
    send(web(3, 'still streaming'))
  })
  await page.goto('/environments/a/deployments/s1')
  await page.getByRole('button', { name: 'Show logs' }).click()
  await expect(page.getByRole('status', { name: 'Log notices' })).toContainText('too many containers')
  await expect(page.getByRole('log', { name: 'Deployment log' })).toContainText('still streaming')
})

test('explains a refused stream', async ({ page }) => {
  await mockBroker(page, () => {}, { refuse: true })
  await page.goto('/environments/a/deployments/s1')
  await page.getByRole('button', { name: 'Show logs' }).click()
  await expect(page.getByRole('alert')).toContainText('not allowed')
})
