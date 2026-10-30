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
  containers: { running: 0, stopped: 0, healthy: 0, unhealthy: 0 },
  images: 0,
  volumes: 0,
}
const BASE = '/api/hosts/a/managed-deployments'
const ROUTES = '/api/hosts/a/proxy/routes'
const yaml = 'services:\n  wp:\n    image: wordpress:6\n'
const created = { id: 's1', name: 'wordpress', status: 'STOPPED', source: 'FILE', lastDeployedAt: null, createdAt: '2026-10-01T10:00:00Z', yaml, envKeys: [] }
const instance = { type: 'TRAEFIK', acmeEmail: 'ops@example.com', defaultCertMode: 'ACME_HTTP', dnsProvider: null, dnsCredentialsHint: null, deploymentId: 'p1', deploymentStatus: 'RUNNING' }
const templates = [
  {
    id: 'wordpress',
    name: 'WordPress',
    description: 'Blog engine',
    env: [],
    spec: { services: [{ name: 'wp', image: 'wordpress:6', ports: [{ host: 8080, container: 80 }], environment: [] }], networks: [], volumes: [] },
  },
]
const candidate = { containerName: 'wordpress-wp-1', image: 'wordpress:6', state: 'running', ports: [80, 8443], suggestedPort: null, published: false }

function mockCommon(api: ApiMock, proxy: unknown) {
  api.mock('GET', '/api/auth/me', { body: { username: 'u', role: 'OPERATOR', setupRequired: false } })
  api.mock('GET', '/api/hosts/overview', { body: [host] })
  api.mock('GET', '/api/hosts/a/deployments', { body: [] })
  api.mock('GET', BASE, { body: [] })
  api.mock('GET', '/api/templates', { body: templates })
  api.mock('GET', '/api/hosts/a/proxy/managed', { body: proxy })
  api.mock('GET', '/api/hosts/a/ports/free', { body: { used: [], suggestions: [{ preferred: 8080, free: 8080 }] } })
  api.mock('POST', '/api/compose/generate', { body: { yaml } })
  api.mock('POST', BASE, { status: 201, body: created })
  api.mock('POST', `${BASE}/s1/deploy`, { status: 202, body: { runId: 'r1', versionId: 'v1' } })
  api.mock('GET', `${BASE}/s1`, { body: created })
  api.mock('GET', `${BASE}/s1/versions`, { body: [] })
  api.mock('GET', `${BASE}/s1/files`, { body: [] })
  api.mock('GET', `${BASE}/s1/webhook`, { body: { enabled: false, createdAt: null, rotatedAt: null } })
  api.mock('GET', `${BASE}/s1/uptime-checks`, { body: [] })
  api.mock('GET', `${ROUTES}/candidates`, { body: [candidate] })
  api.mock('GET', ROUTES, { body: [] })
}

async function startInstall(page: import('@playwright/test').Page) {
  await page.goto('/environments/a/deployments')
  await page.getByRole('button', { name: 'Add deployment' }).click()
  await page.getByRole('radio', { name: 'From template' }).check()
  await page.getByRole('listitem').filter({ hasText: 'WordPress' }).getByRole('button', { name: 'Install' }).click()
  const dialog = page.getByRole('dialog', { name: 'Install WordPress' })
  await expect(dialog.getByLabel('Name', { exact: true })).toHaveValue('wordpress')
  await dialog.getByRole('button', { name: 'Install', exact: true }).click()
}

test('after installing a catalog app the user publishes it with a domain', async ({ page, api }) => {
  mockCommon(api, { instance, setupBlockedReason: null })
  await startInstall(page)

  const step = page.getByRole('dialog', { name: 'WordPress is installing' })
  await step.getByRole('button', { name: 'Publish with domain' }).click()
  const publish = page.getByRole('dialog', { name: /Publish/ }).last()
  await publish.getByLabel('Domain').fill('blog.example.com')

  const created = { id: 'r1', hostname: 'blog.example.com', aliases: [], containerName: 'wordpress-wp-1', targetPort: 80, backendScheme: 'HTTP', pathPrefix: null, entrypoint: null, certMode: 'ACME_HTTP', certificateId: null, redirectHttps: true, basicAuthUsers: [], responseHeaders: {}, advancedActive: false, containerState: 'running' }
  api.mock('POST', ROUTES, { status: 201, body: created })
  const request = page.waitForRequest((r) => r.method() === 'POST' && r.url().endsWith(ROUTES))
  await publish.getByRole('button', { name: 'Publish', exact: true }).click()
  const body = (await request).postDataJSON()
  expect(body).toMatchObject({ hostname: 'blog.example.com', containerName: 'wordpress-wp-1', port: 80 })
  await expect(page).toHaveURL(/\/environments\/a\/deployments\/s1$/)
})

test('skipping the publish step continues to the deployment', async ({ page, api }) => {
  mockCommon(api, { instance, setupBlockedReason: null })
  await startInstall(page)
  await page.getByRole('dialog', { name: 'WordPress is installing' }).getByRole('button', { name: 'Skip' }).click()
  await expect(page).toHaveURL(/\/environments\/a\/deployments\/s1$/)
})

test('without a managed proxy a hint links to the Domains page', async ({ page, api }) => {
  mockCommon(api, { instance: null, setupBlockedReason: null })
  await startInstall(page)
  const step = page.getByRole('dialog', { name: 'WordPress is installing' })
  await expect(step.getByRole('button', { name: 'Publish with domain' })).toHaveCount(0)
  await expect(step.getByRole('link', { name: 'Set up Domains' })).toHaveAttribute('href', '/environments/a/domains')
  await step.getByRole('button', { name: 'Open deployment' }).click()
  await expect(page).toHaveURL(/\/environments\/a\/deployments\/s1$/)
})
