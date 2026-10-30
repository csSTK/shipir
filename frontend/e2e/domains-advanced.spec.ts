import { expect, test, type ApiMock } from './fixtures/api.ts'

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
  dnsProvider: 'cloudflare',
  dnsCredentialsHint: 'CF_…2f9a',
  deploymentId: 'd1',
  deploymentStatus: 'RUNNING',
}
const ROUTES = '/api/hosts/a/proxy/routes'
const candidates = [{ containerName: 'web', image: 'nginx:1', state: 'running', ports: [8080], suggestedPort: 8080, published: false }]
const certificate = { id: 'c1', name: 'Wildcard 2027', domains: ['*.example.com'], notAfter: '2027-01-01T00:00:00Z', usedByRoutes: [] }

function setup(api: ApiMock, role: string, type = 'TRAEFIK') {
  api.mock('GET', '/api/auth/me', { body: { username: 'u', role, setupRequired: false } })
  api.mock('GET', '/api/hosts/overview', { body: [host] })
  api.mock('GET', '/api/hosts/a/proxy/managed', { body: { instance: { ...instance, type }, setupBlockedReason: null } })
  api.mock('GET', '/api/hosts/a/proxy/certificates', { body: [certificate] })
  api.mock('GET', ROUTES, { body: [] })
  api.mock('GET', `${ROUTES}/status`, { body: [] })
  api.mock('GET', `${ROUTES}/candidates`, { body: candidates })
  // the raw configuration editor (ADMIN, advanced mode) loads these
  api.mock('GET', '/api/hosts/a/proxy/config', { body: { type, managed: [], customFileName: 'custom.yml', custom: '', customUpdatedAt: null } })
  api.mock('GET', '/api/hosts/a/proxy/config/custom/versions', { body: [] })
}

test('an operator publishes with advanced options; they are hidden again in simple mode', async ({ page, api }) => {
  setup(api, 'OPERATOR')
  await page.goto('/environments/a/domains')
  await page.getByRole('button', { name: 'Publish a container' }).first().click()
  let dialog = page.getByRole('dialog', { name: 'Publish a container' })
  await expect(dialog.getByLabel('Path prefix')).toHaveCount(0)
  await dialog.getByRole('button', { name: 'Cancel' }).click()

  await page.getByRole('switch', { name: 'Advanced mode' }).check()
  await page.getByRole('button', { name: 'Publish a container' }).first().click()
  dialog = page.getByRole('dialog', { name: 'Publish a container' })
  await dialog.getByRole('radio', { name: /web/ }).check()
  await dialog.getByLabel('Domain', { exact: true }).fill('*.example.com')
  await dialog.getByLabel('HTTPS certificate').selectOption('CUSTOM')
  await dialog.getByLabel('Your certificate').selectOption('c1')
  await dialog.getByLabel('Path prefix').fill('blog')
  await dialog.getByRole('button', { name: 'Publish' }).click()
  await expect(dialog.getByText(/must start with/)).toBeVisible()

  await dialog.getByLabel('Path prefix').fill('/blog')
  await dialog.getByRole('button', { name: 'Add user' }).click()
  await dialog.getByLabel('User name').fill('bob')
  await dialog.getByLabel('Password').fill('s3cret')
  await dialog.getByRole('button', { name: 'Add header' }).click()
  await dialog.getByLabel('Header name').fill('X-Frame-Options')
  await dialog.getByLabel('Header value').fill('DENY')

  const created = {
    id: 'r2', hostname: '*.example.com', aliases: [], containerName: 'web', targetPort: 8080, backendScheme: 'HTTP', pathPrefix: '/blog', entrypoint: null,
    certMode: 'CUSTOM', certificateId: 'c1', redirectHttps: true, basicAuthUsers: ['bob'], responseHeaders: { 'X-Frame-Options': 'DENY' }, advancedActive: true, containerState: 'running',
  }
  api.mock('POST', ROUTES, { status: 201, body: created })
  api.mock('GET', ROUTES, { body: [created] })
  const request = page.waitForRequest((r) => r.method() === 'POST' && r.url().endsWith('/proxy/routes'))
  await dialog.getByRole('button', { name: 'Publish' }).click()
  expect((await request).postDataJSON()).toEqual({
    containerName: 'web',
    hostname: '*.example.com',
    pathPrefix: '/blog',
    certMode: 'CUSTOM',
    certificateId: 'c1',
    basicAuth: { bob: 's3cret' },
    responseHeaders: { 'X-Frame-Options': 'DENY' },
  })
  await expect(dialog).toHaveCount(0)
  const table = page.getByRole('table', { name: 'published domains' })
  await expect(table.getByText('Advanced settings')).toBeVisible()

  // the choice survives a reload
  await page.reload()
  await expect(page.getByRole('switch', { name: 'Advanced mode' })).toBeChecked()
})

test('a server problem with the certificate is shown in the dialog', async ({ page, api }) => {
  setup(api, 'OPERATOR')
  await page.goto('/environments/a/domains')
  await page.getByRole('switch', { name: 'Advanced mode' }).check()
  await page.getByRole('button', { name: 'Publish a container' }).first().click()
  const dialog = page.getByRole('dialog', { name: 'Publish a container' })
  await dialog.getByRole('radio', { name: /web/ }).check()
  await dialog.getByLabel('Domain', { exact: true }).fill('shop.example.com')
  await dialog.getByLabel('Path prefix').fill('/shop')
  api.mock('POST', ROUTES, { status: 400, body: { code: 'UNSUPPORTED_FOR_PROXY', message: 'x' } })
  await dialog.getByRole('button', { name: 'Publish' }).click()
  await expect(dialog.getByText(/not supported by this proxy/)).toBeVisible()
  await expect(dialog).toBeVisible()
})

test('Caddy hides the DNS challenge and the entrypoint', async ({ page, api }) => {
  setup(api, 'OPERATOR', 'CADDY')
  await page.goto('/environments/a/domains')
  await page.getByRole('switch', { name: 'Advanced mode' }).check()
  await page.getByRole('button', { name: 'Publish a container' }).first().click()
  const dialog = page.getByRole('dialog', { name: 'Publish a container' })
  await expect(dialog.getByLabel('HTTPS certificate')).toBeVisible()
  await expect(dialog.getByLabel('Entrypoint')).toHaveCount(0)
  await expect(dialog.getByRole('option', { name: /DNS challenge/ })).toHaveCount(0)
})

test('an admin changes the proxy settings: credentials are never shown, only replaced', async ({ page, api }) => {
  setup(api, 'ADMIN')
  await page.goto('/environments/a/domains')
  await expect(page.getByRole('heading', { name: 'Proxy settings' })).toHaveCount(0)
  await page.getByRole('switch', { name: 'Advanced mode' }).check()
  const panel = page.getByRole('heading', { name: 'Proxy settings' })
  await expect(panel).toBeVisible()
  await expect(page.getByText(/CF_…2f9a/)).toBeVisible()
  await expect(page.getByLabel('DNS provider')).toHaveValue('cloudflare')
  await page.getByRole('button', { name: 'Replace credentials' }).click()
  await page.getByLabel('Variable name').fill('CF_DNS_API_TOKEN')
  await page.getByLabel('Variable value').fill('tok')
  api.mock('PUT', '/api/hosts/a/proxy/managed', { status: 200, body: { instance: { ...instance, dnsCredentialsHint: 'CF_…beef' }, runId: null } })
  const request = page.waitForRequest((r) => r.method() === 'PUT' && r.url().endsWith('/proxy/managed'))
  await page.getByRole('button', { name: 'Save settings' }).click()
  expect((await request).postDataJSON()).toEqual({ dnsCredentials: { CF_DNS_API_TOKEN: 'tok' } })
  await expect(page.getByText('Settings saved.')).toBeVisible()
  await expect(page.getByLabel('Variable value')).toHaveCount(0)
})

test('the certificates link in the dialog closes it and shows the certificates section', async ({ page, api }) => {
  setup(api, 'OPERATOR')
  await page.goto('/environments/a/domains')
  await page.getByRole('switch', { name: 'Advanced mode' }).check()
  await page.getByRole('button', { name: 'Publish a container' }).first().click()
  const dialog = page.getByRole('dialog', { name: 'Publish a container' })
  await dialog.getByLabel('HTTPS certificate').selectOption('CUSTOM')
  await dialog.getByRole('button', { name: 'Upload or manage certificates' }).click()
  await expect(dialog).toHaveCount(0)
  await expect(page.getByRole('heading', { name: 'Your certificates' })).toBeInViewport()
})
