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
const yaml = 'services:\n  wp:\n    image: wordpress:6\n'
const created = { id: 's1', name: 'wordpress', status: 'STOPPED', source: 'FILE', lastDeployedAt: null, createdAt: '2026-10-01T10:00:00Z', yaml, envKeys: ['DB_PASSWORD'] }
const templates = [
  { id: 'nginx', name: 'Nginx', description: 'Web server', env: [], spec: { services: [{ name: 'web', image: 'nginx:1.27', ports: [{ host: 8080, container: 80 }] }], networks: [], volumes: [] } },
  {
    id: 'wordpress',
    name: 'WordPress',
    description: 'Blog engine with MySQL',
    env: [{ key: 'DB_PASSWORD', description: 'Database password', secret: true }],
    spec: {
      services: [
        { name: 'wp', image: 'wordpress:6', environment: [{ key: 'WORDPRESS_DB_PASSWORD', value: null, ref: 'DB_PASSWORD' }] },
        { name: 'db', image: 'mysql:8' },
      ],
      networks: [],
      volumes: [],
    },
  },
]

function mockCommon(api: ApiMock) {
  api.mock('GET', '/api/auth/me', { body: { username: 'u', role: 'OPERATOR', setupRequired: false } })
  api.mock('GET', '/api/hosts/overview', { body: [host] })
  api.mock('GET', '/api/hosts/a/deployments', { body: [] })
  api.mock('GET', BASE, { body: [] })
  api.mock('GET', '/api/templates', { body: templates })
  api.mock('GET', '/api/hosts/a/ports/free', { body: { used: [{ port: 8080, protocol: 'tcp' }], suggestions: [{ preferred: 8080, free: 8083 }] } })
}

test('operator picks a template, fills the variable and creates the deployment', async ({ page, api }) => {
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
  await page.getByRole('radio', { name: 'From template' }).check()

  await page.getByLabel('Search templates').fill('blog')
  await expect(page.getByRole('listitem').filter({ hasText: 'Nginx' })).toHaveCount(0)
  await page.getByRole('listitem').filter({ hasText: 'WordPress' }).getByRole('button', { name: 'Customize' }).click()

  // The wizard is filled in and the name suggested.
  await expect(page.getByLabel('Name', { exact: true })).toHaveValue('wordpress')
  await expect(page.getByLabel('Service 1 image')).toHaveValue('wordpress:6')
  await expect(page.getByLabel('Service 2 image')).toHaveValue('mysql:8')
  await expect(page.getByText('Database password')).toBeVisible()

  await page.getByRole('button', { name: 'Preview YAML' }).click()
  await page.getByRole('button', { name: 'Continue in editor' }).click()
  await expect(page.getByLabel('Variable 1 name')).toHaveValue('DB_PASSWORD')
  await page.getByLabel('Variable 1 value').fill('s3cret')

  const request = page.waitForRequest((r) => r.method() === 'POST' && r.url().endsWith(BASE))
  await page.getByRole('button', { name: 'Create deployment' }).click()
  expect((await request).postDataJSON()).toEqual({ name: 'wordpress', yaml, env: [{ key: 'DB_PASSWORD', value: 's3cret' }] })
  await expect(page).toHaveURL(/\/environments\/a\/deployments\/s1$/)
})

test('template list failure shows an error', async ({ page, api }) => {
  mockCommon(api)
  api.mock('GET', '/api/templates', { status: 500, body: { code: 'INTERNAL', message: 'boom' } })
  await page.goto('/environments/a/deployments')
  await page.getByRole('button', { name: 'Add deployment' }).click()
  await page.getByRole('radio', { name: 'From template' }).check()
  await expect(page.getByRole('button', { name: 'Retry' })).toBeVisible()
})

test('clashing template port is replaced, noticed and stays editable', async ({ page, api }) => {
  mockCommon(api)
  await page.goto('/environments/a/deployments')
  await page.getByRole('button', { name: 'Add deployment' }).click()
  await page.getByRole('radio', { name: 'From template' }).check()
  await page.getByRole('listitem').filter({ hasText: 'Nginx' }).getByRole('button', { name: 'Customize' }).click()
  await expect(page.getByText('Port 8080 was taken, using 8083.')).toBeVisible()
  const host = page.getByLabel('Service 1 port 1 host')
  await expect(host).toHaveValue('8083')
  await host.fill('9000')
  await expect(host).toHaveValue('9000')
})

test('ports lookup failure falls back to the template default', async ({ page, api }) => {
  mockCommon(api)
  api.mock('GET', '/api/hosts/a/ports/free', { status: 500, body: { code: 'INTERNAL', message: 'boom' } })
  await page.goto('/environments/a/deployments')
  await page.getByRole('button', { name: 'Add deployment' }).click()
  await page.getByRole('radio', { name: 'From template' }).check()
  await page.getByRole('listitem').filter({ hasText: 'Nginx' }).getByRole('button', { name: 'Customize' }).click()
  await expect(page.getByLabel('Service 1 port 1 host')).toHaveValue('8080')
  await expect(page.getByText(/was taken/)).toHaveCount(0)
})
