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
  containers: { running: 0, stopped: 0, healthy: 0, unhealthy: 0 },
  images: 0,
  volumes: 0,
}
const BASE = '/api/hosts/a/managed-deployments'

function mockCommon(api: import('./fixtures/api.ts').ApiMock, proxy: unknown, managedType: 'TRAEFIK' | 'CADDY' | null = null) {
  api.mock('GET', '/api/auth/me', { body: { username: 'u', role: 'OPERATOR', setupRequired: false } })
  api.mock('GET', '/api/hosts/overview', { body: [host] })
  api.mock('GET', '/api/hosts/a/deployments', { body: [] })
  api.mock('GET', BASE, { body: [] })
  api.mock('GET', '/api/hosts/a/proxy', { body: proxy })
  api.mock('GET', '/api/hosts/a/proxy/managed', { body: { instance: managedType ? { type: managedType } : null, setupBlockedReason: null } })
}

async function openWizard(page: import('@playwright/test').Page) {
  await page.goto('/environments/a/deployments')
  await page.getByRole('button', { name: 'Add deployment' }).click()
  await page.getByRole('radio', { name: 'Form wizard' }).check()
}

/** Ports, environment, volumes, domain and networks sit in a per-service disclosure that starts closed. */
async function openAdvanced(page: import('@playwright/test').Page, n = 1) {
  await page.getByRole('group', { name: `Service ${n}` }).getByText('Advanced options').click()
}

test('detected proxy prefills the domain section and the domain is sent to generate', async ({ page, api }) => {
  mockCommon(api, { visible: true, proxy: 'TRAEFIK', container: 'traefik', network: 'proxy' })
  api.mock('POST', '/api/compose/generate', { body: { yaml: 'services: {}\n' } })
  await openWizard(page)

  await page.getByLabel('Service 1 name').fill('web')
  await openAdvanced(page)
  await expect(page.getByText(/Traefik detected \(traefik\) on network proxy/)).toBeVisible()
  await page.getByLabel('Service 1 image').fill('nginx:1.27')
  await page.getByLabel('Service 1 domain host').fill('app.example.com')
  await expect(page.getByLabel('Service 1 domain network')).toHaveValue('proxy')
  await page.getByLabel('Service 1 domain port').fill('80')

  const request = page.waitForRequest((r) => r.method() === 'POST' && r.url().endsWith('/api/compose/generate'))
  await page.getByRole('button', { name: 'Preview YAML' }).click()
  const spec = (await request).postDataJSON().spec
  expect(spec.services[0].domain).toEqual({ host: 'app.example.com', port: 80, proxy: 'TRAEFIK', network: 'proxy' })
})

test('without a proxy the Traefik template is offered, invalid domains are caught locally', async ({ page, api, context }) => {
  await context.grantPermissions(['clipboard-read', 'clipboard-write'])
  mockCommon(api, { visible: true, proxy: null, container: null, network: null })
  api.mock('GET', '/api/compose/proxy-templates/traefik', { body: { yaml: 'services:\n  traefik:\n    image: traefik:v3.1\n' } })
  await openWizard(page)
  await openAdvanced(page)

  await expect(page.getByText('No reverse proxy detected on this host.', { exact: false })).toBeVisible()
  await page.getByRole('button', { name: 'Copy Traefik template' }).click()
  await expect(page.getByText('Copied')).toBeVisible()
  expect(await page.evaluate(() => navigator.clipboard.readText())).toContain('traefik:v3.1')

  await page.getByLabel('Service 1 name').fill('web')
  await page.getByLabel('Service 1 image').fill('nginx')
  await page.getByLabel('Service 1 domain host').fill('Not_A_Domain')
  await page.getByRole('button', { name: 'Preview YAML' }).click()
  await expect(page.getByText('Enter a lowercase domain name such as app.example.com.')).toBeVisible()
})

test('two Traefik domains that Traefik would name the same router are flagged before preview', async ({ page, api }) => {
  mockCommon(api, { visible: true, proxy: 'TRAEFIK', container: 'traefik', network: 'proxy' })
  await openWizard(page)

  await page.getByLabel('Service 1 name').fill('web')
  await page.getByLabel('Service 1 image').fill('nginx:1.27')
  await openAdvanced(page)
  await page.getByLabel('Service 1 domain host').fill('a.b-c.com')
  await page.getByLabel('Service 1 domain port').fill('80')
  await page.getByRole('button', { name: 'Add service' }).click()
  await page.getByLabel('Service 2 name').fill('api')
  await page.getByLabel('Service 2 image').fill('nginx:1.27')
  await openAdvanced(page, 2)
  await page.getByLabel('Service 2 domain host').fill('a-b.c.com')
  await page.getByLabel('Service 2 domain port').fill('80')
  await page.getByRole('button', { name: 'Preview YAML' }).click()
  await expect(page.getByText('Traefik gives this domain the same router name as another service', { exact: false })).toBeVisible()
})

test('a host that is not fully visible gets a neutral hint, not "no reverse proxy"', async ({ page, api }) => {
  mockCommon(api, { visible: false, proxy: null, container: null, network: null })
  await openWizard(page)
  await openAdvanced(page)

  await expect(page.getByText('Proxy detection is not available', { exact: false })).toBeVisible()
  await expect(page.getByText('No reverse proxy detected', { exact: false })).toHaveCount(0)
})

test('a host with a managed Caddy gets no Caddy labels and a link to Domains', async ({ page, api }) => {
  mockCommon(api, { visible: true, proxy: null, container: null, network: null }, 'CADDY')
  api.mock('POST', '/api/compose/generate', { body: { yaml: 'services: {}\n' } })
  await openWizard(page)

  await page.getByLabel('Service 1 name').fill('web')
  await page.getByLabel('Service 1 image').fill('nginx:1.27')
  await openAdvanced(page)
  await page.getByLabel('Service 1 domain host').fill('app.example.com')
  await expect(page.getByText('does not read Docker labels', { exact: false })).toBeVisible()
  await expect(page.getByRole('link', { name: 'Open Domains' })).toHaveAttribute('href', '/environments/a/domains')
  await expect(page.getByLabel('Service 1 domain proxy').getByRole('option', { name: 'Caddy' })).toHaveCount(0)

  await page.getByLabel('Service 1 domain port').fill('80')
  const request = page.waitForRequest((r) => r.method() === 'POST' && r.url().endsWith('/api/compose/generate'))
  await page.getByRole('button', { name: 'Preview YAML' }).click()
  const body = (await request).postDataJSON()
  expect(body.hostId).toBe('a')
  expect(body.spec.services[0].domain.proxy).toBe('TRAEFIK')
})
