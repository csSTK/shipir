import { expect, mockDeploymentCards, test, type ApiMock } from './fixtures/api.ts'

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
const LIST = '/api/portainer-import/stacks'
const simple = 'services:\n  web:\n    image: nginx:1.27\n'
const created = { id: 's1', name: 'shop', status: 'STOPPED', source: 'FILE', lastDeployedAt: null, createdAt: '2026-10-01T10:00:00Z', yaml: simple, envKeys: ['PORT'] }
const stacks = [
  { id: 7, name: 'shop', type: 'compose', endpointId: 1, supported: true },
  { id: 9, name: 'swarmy', type: 'swarm', endpointId: 1, supported: false, reason: 'Swarm stacks are not supported.' },
]

function mockCommon(api: ApiMock) {
  api.mock('GET', '/api/auth/me', { body: { username: 'u', role: 'OPERATOR', setupRequired: false } })
  api.mock('GET', '/api/hosts/overview', { body: [host] })
  api.mock('GET', '/api/hosts/a/deployments', { body: [] })
  api.mock('GET', BASE, { body: [] })
}

async function openPortainer(page: import('@playwright/test').Page) {
  await page.goto('/environments/a/deployments')
  await page.getByRole('button', { name: 'Add deployment' }).click()
  await page.getByRole('radio', { name: 'Import from Portainer' }).check()
  await page.getByLabel('Portainer URL').fill('https://portainer.example.com')
  await page.getByLabel('API token').fill('ptr_secret')
  await page.getByRole('button', { name: 'Connect' }).click()
}

test('connects to Portainer, imports a stack and creates the deployment', async ({ page, api }) => {
  mockCommon(api)
  api.mock('POST', LIST, { body: stacks })
  api.mock('POST', `${LIST}/7`, { body: { name: 'shop', yaml: simple, env: [{ key: 'PORT', value: '8080' }] } })
  api.mock('POST', '/api/compose/generate', { body: { yaml: simple } })
  api.mock('POST', BASE, { status: 201, body: created })
  api.mock('GET', `${BASE}/s1`, { body: created })
  mockDeploymentCards(api, `${BASE}/s1`)

  await openPortainer(page)
  await expect(page.getByRole('button', { name: 'Import swarmy' })).toBeDisabled()
  await expect(page.getByText('Swarm stacks are not supported.')).toBeVisible()
  await page.getByRole('button', { name: 'Import shop' }).click()

  await expect(page.getByLabel('Name', { exact: true })).toHaveValue('shop')
  await expect(page.getByRole('radio', { name: 'Form wizard' })).toBeChecked()
  await expect(page.getByLabel('Service 1 image')).toHaveValue('nginx:1.27')
  await page.getByRole('button', { name: 'Preview YAML' }).click()
  await page.getByRole('button', { name: 'Continue in editor' }).click()
  await expect(page.getByLabel('Variable 1 value')).toHaveValue('8080')

  const request = page.waitForRequest((r) => r.method() === 'POST' && r.url().endsWith(BASE))
  await page.getByRole('button', { name: 'Create deployment' }).click()
  expect((await request).postDataJSON()).toEqual({ name: 'shop', yaml: simple, env: [{ key: 'PORT', value: '8080' }] })
  await expect(page).toHaveURL(/\/environments\/a\/deployments\/s1$/)
})

test('shows a rejected token inline and lists nothing', async ({ page, api }) => {
  mockCommon(api)
  api.mock('POST', LIST, { status: 422, body: { code: 'PORTAINER_AUTH_FAILED', message: 'raw' } })

  await openPortainer(page)
  await expect(page.getByRole('alert').filter({ hasText: 'Portainer rejected' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Import shop' })).toHaveCount(0)
})

test('shows an egress-denied address inline', async ({ page, api }) => {
  mockCommon(api)
  api.mock('POST', LIST, { status: 400, body: { code: 'EGRESS_DENIED', message: 'raw' } })

  await openPortainer(page)
  await expect(page.getByRole('alert').filter({ hasText: 'not allowed' })).toBeVisible()
})
