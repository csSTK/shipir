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
const simple = 'services:\n  web:\n    image: nginx:1.27\n'
const withBuild = 'services:\n  web:\n    build: .\n'
const created = { id: 's1', name: 'shop', status: 'STOPPED', source: 'FILE', lastDeployedAt: null, createdAt: '2026-10-01T10:00:00Z', yaml: simple, envKeys: ['PORT'] }

function mockCommon(api: ApiMock) {
  api.mock('GET', '/api/auth/me', { body: { username: 'u', role: 'OPERATOR', setupRequired: false } })
  api.mock('GET', '/api/hosts/overview', { body: [host] })
  api.mock('GET', '/api/hosts/a/deployments', { body: [] })
  api.mock('GET', BASE, { body: [] })
}

async function openImport(page: import('@playwright/test').Page) {
  await page.goto('/environments/a/deployments')
  await page.getByRole('button', { name: 'Add deployment' }).click()
  await page.getByRole('radio', { name: 'Import file' }).check()
}

test('imports a compose file the wizard can represent, prefilled with name and env rows', async ({ page, api }) => {
  mockCommon(api)
  api.mock('POST', '/api/compose/generate', { body: { yaml: simple } })
  api.mock('POST', BASE, { status: 201, body: created })
  api.mock('GET', `${BASE}/s1`, { body: created })
  mockDeploymentCards(api, `${BASE}/s1`)

  await openImport(page)
  await page.getByLabel('Compose file (.yml, .yaml)', { exact: true }).setInputFiles({ name: 'shop.yml', mimeType: 'application/yaml', buffer: Buffer.from(simple) })
  await page.getByLabel('Environment file (optional)', { exact: true }).setInputFiles({ name: 'stack.env', mimeType: 'text/plain', buffer: Buffer.from('PORT=8080\n') })

  await expect(page.getByLabel('Name', { exact: true })).toHaveValue('shop')
  await expect(page.getByRole('radio', { name: 'Form wizard' })).toBeChecked()
  await expect(page.getByLabel('Service 1 image')).toHaveValue('nginx:1.27')
  await expect(page.getByText(/form wizard cannot show this file/i)).toHaveCount(0)

  await page.getByRole('button', { name: 'Preview YAML' }).click()
  await page.getByRole('button', { name: 'Continue in editor' }).click()
  await expect(page.getByLabel('Variable 1 name')).toHaveValue('PORT')
  await expect(page.getByLabel('Variable 1 value')).toHaveValue('8080')

  const request = page.waitForRequest((r) => r.method() === 'POST' && r.url().endsWith(BASE))
  await page.getByRole('button', { name: 'Create deployment' }).click()
  expect((await request).postDataJSON()).toEqual({ name: 'shop', yaml: simple, env: [{ key: 'PORT', value: '8080' }] })
  await expect(page).toHaveURL(/\/environments\/a\/deployments\/s1$/)
})

test('falls back to the YAML editor with a hint when the wizard cannot represent the file', async ({ page, api }) => {
  mockCommon(api)
  api.mock('POST', BASE, { status: 201, body: created })
  api.mock('GET', `${BASE}/s1`, { body: created })
  mockDeploymentCards(api, `${BASE}/s1`)

  await openImport(page)
  await page.getByLabel('Compose file (.yml, .yaml)', { exact: true }).setInputFiles({ name: 'shop.yaml', mimeType: 'application/yaml', buffer: Buffer.from(withBuild) })

  await expect(page.getByRole('radio', { name: 'YAML' })).toBeChecked()
  await expect(page.getByRole('status').filter({ hasText: 'form wizard cannot show this file' })).toContainText("'build' is not supported")

  const request = page.waitForRequest((r) => r.method() === 'POST' && r.url().endsWith(BASE))
  await page.getByRole('button', { name: 'Create deployment' }).click()
  expect((await request).postDataJSON()).toEqual({ name: 'shop', yaml: withBuild, env: [] })
})

test('an oversize env file shows an inline error and nothing is imported', async ({ page, api }) => {
  mockCommon(api)
  await openImport(page)
  await page.getByLabel('Environment file (optional)', { exact: true }).setInputFiles({ name: 'big.env', mimeType: 'text/plain', buffer: Buffer.from('A=' + 'x'.repeat(512 * 1024)) })
  await expect(page.getByText('big.env is too large')).toBeVisible()
  await expect(page.getByRole('radio', { name: 'Form wizard' })).toBeHidden()
  await expect(page.getByRole('button', { name: 'Create deployment' })).toBeHidden()
})

test('env rows from an env-only import do not show up in the New file source', async ({ page, api }) => {
  mockCommon(api)
  await openImport(page)
  await page.getByLabel('Environment file (optional)', { exact: true }).setInputFiles({ name: 'stack.env', mimeType: 'text/plain', buffer: Buffer.from('PORT=8080\n') })
  await expect(page.getByLabel('Variable 1 name')).toHaveValue('PORT')

  await page.getByRole('radio', { name: 'New file' }).check()
  await expect(page.getByLabel('Variable 1 name')).toHaveCount(0)
})
