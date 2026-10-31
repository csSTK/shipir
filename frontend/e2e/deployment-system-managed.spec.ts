import { expect, mockDeploymentCards, test } from './fixtures/api.ts'

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
const BASE = '/api/hosts/a/managed-deployments'
const proxyLive = { name: 'shipir-proxy', containerCount: 1, runningCount: 1, status: 'RUNNING', workingDir: null, managed: true, managedDeploymentId: 'p1', systemManaged: true }
const proxyManaged = { id: 'p1', name: 'shipir-proxy', status: 'RUNNING', source: 'FILE', lastDeployedAt: null, createdAt: '2026-10-01T10:00:00Z', systemManaged: true }
const container = { id: 'c1', name: 'proxy', image: 'traefik:3', state: 'running', status: 'Up', health: null, created: '2026-10-01T10:00:00Z', ports: [], composeProject: 'shipir-proxy', composeService: 'traefik' }

function mockCommon(api: import('./fixtures/api.ts').ApiMock, role: string) {
  api.mock('GET', '/api/auth/me', { body: { username: 'u', role, setupRequired: false } })
  api.mock('GET', '/api/hosts/overview', { body: [host] })
  api.mock('GET', '/api/hosts/a/deployments', { body: [proxyLive] })
  api.mock('GET', BASE, { body: [proxyManaged] })
  api.mock('GET', '/api/hosts/a/containers', { body: [container] })
  api.mock('GET', '/api/hosts/a/images', { body: [] })
}

test('operator sees the proxy as managed via Domains without deploy, stop or detail access', async ({ page, api }) => {
  mockCommon(api, 'OPERATOR')
  api.mock('GET', `${BASE}/p1`, { status: 403, body: { code: 'FORBIDDEN', message: 'Access denied' } })
  api.mock('GET', `${BASE}/p1/versions`, { status: 403, body: { code: 'FORBIDDEN', message: 'Access denied' } })

  await page.goto('/environments/a/deployments')
  await expect(page.getByRole('link', { name: 'Managed via Domains' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Deploy shipir-proxy' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Stop shipir-proxy' })).toHaveCount(0)

  await page.goto('/environments/a/deployments/p1')
  await expect(page.getByRole('heading', { name: 'Managed via Domains' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Delete' })).toHaveCount(0)

  await page.goto('/environments/a/containers')
  await expect(page.getByText('Managed via Domains')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Stop proxy' })).toHaveCount(0)
})

test('admin keeps the actions on the proxy deployment and sees the hint', async ({ page, api }) => {
  mockCommon(api, 'ADMIN')
  api.mock('GET', `${BASE}/p1`, { body: { ...proxyManaged, yaml: 'services:\n  traefik:\n    image: traefik:3', envKeys: [], profiles: [] } })
  mockDeploymentCards(api, `${BASE}/p1`, [])

  await page.goto('/environments/a/deployments/p1')
  await expect(page.getByRole('note')).toContainText('managed through Domains')
  await expect(page.getByRole('button', { name: 'Delete' })).toBeVisible()
})
