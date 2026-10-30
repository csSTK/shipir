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
  containers: { running: 2, stopped: 0, healthy: 2, unhealthy: 0 },
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

const ROUTES = '/api/hosts/a/proxy/routes'

const route = {
  id: 'r1',
  hostname: 'blog.example.com',
  aliases: [],
  containerName: 'blog',
  targetPort: 2368,
  backendScheme: 'HTTP',
  pathPrefix: null,
  entrypoint: null,
  certMode: 'ACME_HTTP',
  certificateId: null,
  redirectHttps: true,
  basicAuthUsers: [],
  responseHeaders: {},
  advancedActive: false,
  containerState: 'running',
}
const candidates = [
  { containerName: 'web', image: 'nginx:1', state: 'running', ports: [8080], suggestedPort: 8080, published: false },
  { containerName: 'api', image: 'app:2', state: 'running', ports: [3000, 9000], suggestedPort: null, published: false },
]

function setup(api: import('./fixtures/api.ts').ApiMock, role: string, routes: unknown[], statuses: unknown[] = []) {
  api.mock('GET', '/api/auth/me', { body: { username: 'u', role, setupRequired: false } })
  api.mock('GET', '/api/hosts/overview', { body: [host] })
  api.mock('GET', '/api/hosts/a/proxy/managed', { body: { instance, setupBlockedReason: null } })
  api.mock('GET', '/api/hosts/a/proxy/certificates', { body: [] })
  api.mock('GET', ROUTES, { body: routes })
  api.mock('GET', `${ROUTES}/status`, { body: statuses })
  api.mock('GET', `${ROUTES}/candidates`, { body: candidates })
}

test('an operator publishes a container: invalid domain is caught, then the route appears with its certificate status', async ({ page, api }) => {
  setup(api, 'OPERATOR', [])
  await page.goto('/environments/a/domains')
  await expect(page.getByText('No domains published yet')).toBeVisible()

  await page.getByRole('button', { name: 'Publish a container' }).click()
  const dialog = page.getByRole('dialog', { name: 'Publish a container' })
  await dialog.getByRole('searchbox', { name: 'Search containers' }).fill('we')
  await expect(dialog.getByRole('radio', { name: /api/ })).toHaveCount(0)
  await dialog.getByRole('radio', { name: /web/ }).check()
  await expect(dialog.getByText('Detected automatically.')).toBeVisible()

  await dialog.getByLabel('Domain').fill('Shop.example.com')
  await expect(dialog.getByText('Use lower-case letters only.')).toBeVisible()
  await dialog.getByLabel('Domain').fill('shop.example.com')
  await expect(dialog.getByText('Use lower-case letters only.')).toHaveCount(0)

  const created = { ...route, id: 'r2', hostname: 'shop.example.com', containerName: 'web', targetPort: 8080 }
  api.mock('POST', ROUTES, { status: 201, body: created })
  api.mock('GET', ROUTES, { body: [created] })
  api.mock('GET', `${ROUTES}/status`, { body: [{ hostname: 'shop.example.com', status: 'PENDING', notAfter: null, lastError: null }] })
  await dialog.getByRole('button', { name: 'Publish' }).click()

  await expect(dialog).toHaveCount(0)
  const table = page.getByRole('table', { name: 'published domains' })
  await expect(table.getByRole('link', { name: 'shop.example.com' })).toHaveAttribute('href', 'https://shop.example.com')
  await expect(table.getByText('Waiting for certificate')).toBeVisible()
})

test('a domain that is already used and an ambiguous port are shown inline', async ({ page, api }) => {
  setup(api, 'OPERATOR', [route])
  await page.goto('/environments/a/domains')
  await page.getByRole('button', { name: 'Publish a container' }).click()
  const dialog = page.getByRole('dialog', { name: 'Publish a container' })
  await dialog.getByRole('radio', { name: /api/ }).check()
  await dialog.getByLabel('Domain').fill('blog.example.com')

  await dialog.getByRole('button', { name: 'Publish' }).click()
  await expect(dialog.getByText('Choose the port the container listens on.')).toBeVisible()
  await dialog.getByRole('combobox', { name: 'Port' }).selectOption('9000')

  api.mock('POST', ROUTES, { status: 409, body: { code: 'ROUTE_HOST_TAKEN', message: 'taken' } })
  await dialog.getByRole('button', { name: 'Publish' }).click()
  await expect(dialog.getByText('This domain is already used by another route.')).toBeVisible()
  await expect(dialog).toBeVisible()
})

test('an operator unpublishes a domain after confirming; the failure case keeps the dialog open', async ({ page, api }) => {
  setup(api, 'OPERATOR', [route, { ...route, id: 'r3', hostname: 'old.example.com', advancedActive: true }], [
    { hostname: 'blog.example.com', status: 'ISSUED', notAfter: '2027-01-01T00:00:00Z', lastError: null },
    { hostname: 'old.example.com', status: 'FAILED', notAfter: null, lastError: 'rate limited by the CA' },
  ])
  await page.goto('/environments/a/domains')
  const table = page.getByRole('table', { name: 'published domains' })
  await expect(table.getByText('Certificate issued')).toBeVisible()
  await expect(table.getByText('rate limited by the CA')).toBeVisible()
  await expect(table.getByText('Advanced settings')).toBeVisible()

  await page.getByRole('button', { name: 'More actions for blog.example.com' }).click()
  await page.getByRole('menuitem', { name: 'Unpublish' }).click()
  const confirm = page.getByRole('dialog')
  await expect(confirm.getByText(/blog\.example\.com/).first()).toBeVisible()
  api.mock('DELETE', `${ROUTES}/r1`, { status: 502, body: { code: 'PROXY_SYNC_FAILED', message: 'x' } })
  await confirm.getByRole('button', { name: 'Unpublish' }).click()
  await expect(confirm.getByText(/could not update the proxy/i)).toBeVisible()

  api.mock('DELETE', `${ROUTES}/r1`, { status: 204 })
  api.mock('GET', ROUTES, { body: [{ ...route, id: 'r3', hostname: 'old.example.com' }] })
  await confirm.getByRole('button', { name: 'Unpublish' }).click()
  await expect(confirm).toHaveCount(0)
  await expect(table.getByRole('link', { name: 'blog.example.com' })).toHaveCount(0)
})

test('a viewer sees the published domains without any action', async ({ page, api }) => {
  setup(api, 'VIEWER', [route], [{ hostname: 'blog.example.com', status: 'ISSUED', notAfter: null, lastError: null }])
  await page.goto('/environments/a/domains')
  const table = page.getByRole('table', { name: 'published domains' })
  await expect(table.getByRole('link', { name: 'blog.example.com' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Publish a container' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: /More actions for blog/ })).toHaveCount(0)
})
