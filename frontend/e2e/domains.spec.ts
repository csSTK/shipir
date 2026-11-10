import { expect, test } from './fixtures/api.ts'
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
  containers: { running: 1, stopped: 0, healthy: 1, unhealthy: 0 },
  images: 1,
  volumes: 0,
}
const instance = {
  type: 'TRAEFIK',
  acmeEmail: 'ops@example.com',
  defaultCertMode: 'ACME_HTTP',
  dnsProvider: null,
  dnsCredentialsHint: null,
  deploymentId: 'd1',
  deploymentStatus: 'RUNNING',
}
const DISK_USAGE = {
  images: { count: 12, totalSize: 1024 ** 3, reclaimableSize: 0 },
  containers: { count: 3, totalSize: 0, reclaimableSize: 0 },
  volumes: { count: 4, totalSize: 0, reclaimableSize: 0 },
  buildCache: { count: 0, totalSize: 0, reclaimableSize: 0 },
}

const PATH = '/api/hosts/a/proxy/managed'

function signIn(api: import('./fixtures/api.ts').ApiMock, role: string) {
  api.mock('GET', '/api/auth/me', { body: { username: 'u', role, setupRequired: false } })
  api.mock('GET', '/api/hosts/overview', { body: [host] })
  api.mock('GET', '/api/hosts/a/deployments', { body: [] })
  api.mock('GET', '/api/hosts/a/networks', { body: [] })
}

test('the sidebar and the dashboard link to Domains', async ({ page, api }) => {
  signIn(api, 'VIEWER')
  api.mock('GET', PATH, { body: { instance: null, setupBlockedReason: null } })
  api.mock('GET', '/api/hosts/a/disk-usage', { body: DISK_USAGE })
  await page.goto('/environments/a')
  await expect(page.getByRole('link', { name: 'Domains' }).first()).toBeVisible()
  await page.getByRole('link', { name: 'Domains' }).first().click()
  await expect(page.getByRole('heading', { name: 'Domains', level: 1 })).toBeVisible()
})

test('an admin sets up the proxy from empty to ready', async ({ page, api }) => {
  signIn(api, 'ADMIN')
  api.mock('GET', PATH, { body: { instance: null, setupBlockedReason: null } })
  api.mock('POST', PATH, { status: 201, body: { instance, runId: 'dep-1' } })
  api.mock('GET', `${PATH.replace('managed', 'certificates')}`, { body: [] })
  api.mock('GET', `${PATH.replace('managed', 'routes')}`, { body: [] })
  api.mock('GET', `${PATH.replace('managed', 'routes/status')}`, { body: [] })
  await mockBroker(page, (send) => {
    send({ seq: 1, type: 'LINE', line: 'Container traefik Started' })
    api.mock('GET', PATH, { body: { instance, setupBlockedReason: null } })
    send({ seq: 2, type: 'STATUS', status: 'DONE' })
  })
  await page.goto('/environments/a/domains')
  await expect(page.getByRole('radio', { name: /Traefik/ })).toBeChecked()
  await page.getByRole('button', { name: 'Set up proxy' }).click()
  await expect(page.getByText('Enter an e-mail address for Let’s Encrypt.')).toBeVisible()
  await page.getByLabel('E-mail for Let’s Encrypt').fill('ops@example.com')
  await page.getByRole('button', { name: 'Set up proxy' }).click()
  await expect(page.getByRole('log', { name: 'Output lines' })).toContainText('Container traefik Started')
  await expect(page.getByText('No domains published yet')).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Traefik' })).toBeVisible()
})

test('a blocked environment explains why and offers no form', async ({ page, api }) => {
  signIn(api, 'ADMIN')
  api.mock('GET', PATH, { body: { instance: null, setupBlockedReason: 'EXISTING_PROXY' } })
  await page.goto('/environments/a/domains')
  await expect(page.getByText(/already runs a reverse proxy/)).toBeVisible()
  await expect(page.getByRole('button', { name: 'Set up proxy' })).toHaveCount(0)
})

test('a 409 on setup is shown in plain language', async ({ page, api }) => {
  signIn(api, 'ADMIN')
  api.mock('GET', PATH, { body: { instance: null, setupBlockedReason: null } })
  api.mock('POST', PATH, { status: 409, body: { code: 'PROXY_SETUP_BLOCKED', message: 'blocked' } })
  await page.goto('/environments/a/domains')
  await page.getByLabel('E-mail for Let’s Encrypt').fill('ops@example.com')
  await page.getByRole('button', { name: 'Set up proxy' }).click()
  await expect(page.getByRole('alert')).toContainText('Another proxy is already running')
})

test('a viewer sees a read-only explanation', async ({ page, api }) => {
  signIn(api, 'VIEWER')
  api.mock('GET', PATH, { body: { instance: null, setupBlockedReason: null } })
  await page.goto('/environments/a/domains')
  await expect(page.getByText(/Only administrators can set up/)).toBeVisible()
  await expect(page.getByRole('radio')).toHaveCount(0)
})
