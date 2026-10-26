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
const yaml = 'services:\n  web:\n    image: nginx:1.27\n    networks:\n      - front\n  db:\n    image: postgres:16\nnetworks:\n  front: {}\n'
const created = { id: 's1', name: 'shop', status: 'STOPPED', source: 'FILE', lastDeployedAt: null, createdAt: '2026-10-01T10:00:00Z', yaml, envKeys: ['DB_PASSWORD'] }

function mockCommon(api: import('./fixtures/api.ts').ApiMock) {
  api.mock('GET', '/api/auth/me', { body: { username: 'u', role: 'OPERATOR', setupRequired: false } })
  api.mock('GET', '/api/hosts/overview', { body: [host] })
  api.mock('GET', '/api/hosts/a/deployments', { body: [] })
  api.mock('GET', BASE, { body: [] })
}

test('operator builds a two-service deployment in the wizard, previews it and creates it', async ({ page, api }) => {
  mockCommon(api)
  api.mock('POST', '/api/compose/generate', { body: { yaml } })
  api.mock('POST', BASE, { status: 201, body: created })
  api.mock('GET', `${BASE}/s1`, { body: created })
  api.mock('GET', `${BASE}/s1/versions`, { body: [] })
  api.mock('GET', `${BASE}/s1/files`, { body: [] })
  api.mock('GET', `${BASE}/s1/webhook`, { body: { enabled: false, createdAt: null, rotatedAt: null } })
  api.mock('GET', `${BASE}/s1/uptime-checks`, { body: [] })

  await page.goto('/environments/a/deployments')
  await page.getByRole('button', { name: 'Add deployment' }).click()
  await page.getByLabel('Name', { exact: true }).fill('shop')
  await page.getByRole('radio', { name: 'Form wizard' }).check()

  await page.getByLabel('Service 1 name').fill('web')
  await page.getByLabel('Service 1 image').fill('nginx:1.27')
  await page.getByRole('button', { name: 'Add service' }).click()
  await page.getByLabel('Service 2 name').fill('db')
  await page.getByLabel('Service 2 image').fill('postgres:16')

  await page.getByRole('button', { name: 'Add network' }).click()
  await page.getByLabel('Network 1 name').fill('front')
  await page.getByRole('group', { name: 'Service 1' }).getByRole('checkbox', { name: 'front' }).check()

  await page.getByRole('button', { name: 'Add env var to service 2' }).click()
  await page.getByLabel('Service 2 env 1 name').fill('POSTGRES_PASSWORD')
  await page.getByRole('group', { name: 'Service 2' }).getByLabel('From deployment variable').check()
  await page.getByLabel('Service 2 env 1 reference').fill('DB_PASSWORD')

  await page.getByRole('button', { name: 'Preview YAML' }).click()
  await expect(page.getByRole('heading', { name: 'Generated Compose file' })).toBeVisible()
  await page.getByRole('button', { name: 'Continue in editor' }).click()

  // The referenced variable is waiting in the env editor, ready for its value.
  await expect(page.getByLabel('Variable 1 name')).toHaveValue('DB_PASSWORD')
  await page.getByLabel('Variable 1 value').fill('s3cret')

  const request = page.waitForRequest((r) => r.method() === 'POST' && r.url().endsWith(BASE))
  await page.getByRole('button', { name: 'Create deployment' }).click()
  const body = (await request).postDataJSON()
  expect(body).toEqual({ name: 'shop', yaml, env: [{ key: 'DB_PASSWORD', value: 's3cret' }] })
  await expect(page).toHaveURL(/\/environments\/a\/deployments\/s1$/)
})

test('invalid wizard input is caught locally and server violations are listed', async ({ page, api }) => {
  mockCommon(api)
  api.mock('POST', '/api/compose/generate', {
    status: 400,
    body: { code: 'INVALID_COMPOSE_SPEC', message: 'invalid', details: ['services[0].ports[0]: host port 80 is used twice'] },
  })

  await page.goto('/environments/a/deployments')
  await page.getByRole('button', { name: 'Add deployment' }).click()
  await page.getByRole('radio', { name: 'Form wizard' }).check()

  await page.getByRole('button', { name: 'Preview YAML' }).click()
  await expect(page.getByText('Fix the highlighted fields first.')).toBeVisible()
  await expect(page.getByText('Use lowercase letters, digits, hyphens and underscores.')).toBeVisible()

  await page.getByLabel('Service 1 name').fill('web')
  await page.getByLabel('Service 1 image').fill('nginx')
  await page.getByRole('button', { name: 'Add port to service 1' }).click()
  await page.getByLabel('Service 1 port 1 container').fill('99999')
  await page.getByRole('button', { name: 'Preview YAML' }).click()
  await expect(page.getByText('Use a port from 1 to 65535.')).toBeVisible()

  await page.getByLabel('Service 1 port 1 container').fill('80')
  await page.getByRole('button', { name: 'Preview YAML' }).click()
  await expect(page.getByText('services[0].ports[0]: host port 80 is used twice')).toBeVisible()
})
