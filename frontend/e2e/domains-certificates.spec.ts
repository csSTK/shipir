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

const MANAGED = '/api/hosts/a/proxy/managed'
const CERTS = '/api/hosts/a/proxy/certificates'
const DAY = 24 * 60 * 60 * 1000
const inDays = (n: number) => new Date(Date.now() + n * DAY).toISOString()

const wildcard = { id: 'c1', name: 'wildcard', domains: ['*.example.com'], notAfter: inDays(200), usedByRoutes: ['app.example.com'] }
const shop = { id: 'c2', name: 'shop', domains: ['shop.example.com'], notAfter: inDays(10), usedByRoutes: [] }

function setup(api: import('./fixtures/api.ts').ApiMock, role: string, certs: unknown[]) {
  api.mock('GET', '/api/auth/me', { body: { username: 'u', role, setupRequired: false } })
  api.mock('GET', '/api/hosts/overview', { body: [host] })
  api.mock('GET', MANAGED, { body: { instance, setupBlockedReason: null } })
  api.mock('GET', CERTS, { body: certs })
}

const PEM = (kind: string) => `-----BEGIN ${kind}-----\nMIIB\n-----END ${kind}-----`

test('an admin uploads a certificate; a mismatched key is shown at the key field', async ({ page, api }) => {
  setup(api, 'ADMIN', [])
  await page.goto('/environments/a/domains')
  await expect(page.getByText('No certificates yet')).toBeVisible()

  await page.getByRole('button', { name: 'Upload certificate' }).click()
  const dialog = page.getByRole('dialog')
  await dialog.getByLabel('Name').fill('wildcard')
  await dialog.getByLabel('Certificate chain').fill(PEM('CERTIFICATE'))
  await dialog.getByLabel('Private key').fill(PEM('PRIVATE KEY'))
  api.mock('POST', CERTS, { status: 400, body: { code: 'KEY_MISMATCH', message: 'mismatch' } })
  await dialog.getByRole('button', { name: 'Upload' }).click()
  await expect(dialog.getByText(/key does not belong to this certificate/)).toBeVisible()
  await expect(dialog.getByLabel('Private key')).toHaveValue('')

  // fix the key and succeed
  await dialog.getByLabel('Private key').fill(PEM('PRIVATE KEY'))
  api.mock('POST', CERTS, { status: 201, body: wildcard })
  api.mock('GET', CERTS, { body: [wildcard] })
  await dialog.getByRole('button', { name: 'Upload' }).click()
  await expect(dialog).toHaveCount(0)
  const table = page.getByRole('table', { name: 'certificates' })
  await expect(table.getByText('wildcard')).toBeVisible()
  await expect(table.getByText('app.example.com')).toBeVisible()
})

test('an admin renews a certificate with a new chain and key', async ({ page, api }) => {
  setup(api, 'ADMIN', [wildcard])
  await page.goto('/environments/a/domains')
  await page.getByRole('button', { name: 'Renew wildcard' }).click()
  const dialog = page.getByRole('dialog')
  await expect(dialog.getByLabel('Name')).toHaveCount(0)
  await dialog.getByLabel('Certificate chain').fill(PEM('CERTIFICATE'))
  await dialog.getByLabel('Private key').fill(PEM('PRIVATE KEY'))
  api.mock('PUT', `${CERTS}/c1`, { body: { ...wildcard, notAfter: inDays(365) } })
  await dialog.getByRole('button', { name: 'Renew' }).click()
  await expect(dialog).toHaveCount(0)
})

test('certificates expiring within 30 days carry a warning', async ({ page, api }) => {
  setup(api, 'VIEWER', [wildcard, shop])
  await page.goto('/environments/a/domains')
  const table = page.getByRole('table', { name: 'certificates' })
  await expect(table.getByText('10 days left')).toBeVisible()
  await expect(table.getByText('200 days left')).toHaveCount(0)
  // read-only for non-admins
  await expect(page.getByRole('button', { name: 'Upload certificate' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Renew wildcard' })).toHaveCount(0)
})

test('deleting a certificate that is in use names the routes; an unused one is deleted', async ({ page, api }) => {
  setup(api, 'ADMIN', [wildcard, shop])
  await page.goto('/environments/a/domains')

  await page.getByRole('button', { name: 'More actions for wildcard' }).click()
  await page.getByRole('menuitem', { name: 'Delete' }).click()
  api.mock('DELETE', `${CERTS}/c1`, { status: 409, body: { code: 'CERTIFICATE_IN_USE', message: 'in use', details: ['app.example.com'] } })
  const dialog = page.getByRole('dialog')
  await dialog.getByRole('button', { name: 'Delete' }).click()
  await expect(dialog.getByRole('alert')).toContainText('still used by app.example.com')
  await dialog.getByRole('button', { name: 'Cancel' }).click()

  await page.getByRole('button', { name: 'More actions for shop' }).click()
  await page.getByRole('menuitem', { name: 'Delete' }).click()
  api.mock('DELETE', `${CERTS}/c2`, { status: 204 })
  api.mock('GET', CERTS, { body: [wildcard] })
  await page.getByRole('dialog').getByRole('button', { name: 'Delete' }).click()
  await expect(page.getByRole('table', { name: 'certificates' }).getByText('shop')).toHaveCount(0)
})
