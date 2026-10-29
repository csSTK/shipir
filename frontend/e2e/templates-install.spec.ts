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
const created = { id: 's1', name: 'wordpress', status: 'STOPPED', source: 'FILE', lastDeployedAt: null, createdAt: '2026-10-01T10:00:00Z', yaml, envKeys: ['DB_PASSWORD', 'DB_USER'] }
const templates = [
  {
    id: 'wordpress',
    name: 'WordPress',
    description: 'Blog engine',
    env: [
      { key: 'DB_PASSWORD', description: 'Database password', secret: true, generate: true },
      { key: 'DB_USER', description: 'Database user', secret: false, defaultValue: 'wp' },
    ],
    spec: {
      services: [{ name: 'wp', image: 'wordpress:6', ports: [{ host: 8080, container: 80 }], environment: [{ key: 'WORDPRESS_DB_PASSWORD', value: null, ref: 'DB_PASSWORD' }] }],
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
  api.mock('GET', '/api/hosts/a/ports/free', { body: { used: [], suggestions: [{ preferred: 8080, free: 8080 }] } })
  api.mock('POST', '/api/compose/generate', { body: { yaml } })
  api.mock('GET', `${BASE}/s1`, { body: created })
  api.mock('GET', `${BASE}/s1/versions`, { body: [] })
  api.mock('GET', `${BASE}/s1/files`, { body: [] })
  api.mock('GET', `${BASE}/s1/webhook`, { body: { enabled: false, createdAt: null, rotatedAt: null } })
  api.mock('GET', `${BASE}/s1/uptime-checks`, { body: [] })
}

async function openPicker(page: import('@playwright/test').Page) {
  await page.goto('/environments/a/deployments')
  await page.getByRole('button', { name: 'Add deployment' }).click()
  await page.getByRole('radio', { name: 'From template' }).check()
}

test('one click installs a template with its defaults and opens the deployment', async ({ page, api }) => {
  mockCommon(api)
  api.mock('POST', BASE, { status: 201, body: created })
  api.mock('POST', `${BASE}/s1/deploy`, { status: 202, body: { runId: 'r1', versionId: 'v1' } })

  await openPicker(page)
  await page.getByRole('listitem').filter({ hasText: 'WordPress' }).getByRole('button', { name: 'Install' }).click()

  const dialog = page.getByRole('dialog', { name: 'Install WordPress' })
  await expect(dialog.getByLabel('Name', { exact: true })).toHaveValue('wordpress')
  await expect(dialog.getByLabel('DB_USER')).toHaveValue('wp')
  await expect(dialog.getByRole('textbox', { name: 'DB_PASSWORD' })).not.toHaveValue('')
  await expect(dialog.getByRole('textbox', { name: 'DB_PASSWORD' })).toHaveAttribute('type', 'password')
  await dialog.getByRole('button', { name: 'Show DB_PASSWORD' }).click()
  await expect(dialog.getByRole('textbox', { name: 'DB_PASSWORD' })).toHaveAttribute('type', 'text')

  const create = page.waitForRequest((r) => r.method() === 'POST' && r.url().endsWith(BASE))
  const deploy = page.waitForRequest((r) => r.method() === 'POST' && r.url().endsWith(`${BASE}/s1/deploy`))
  await dialog.getByRole('button', { name: 'Install', exact: true }).click()
  const body = (await create).postDataJSON()
  expect(body.name).toBe('wordpress')
  expect(body.yaml).toBe(yaml)
  expect(body.env).toEqual([
    { key: 'DB_PASSWORD', value: expect.stringMatching(/^[A-Za-z0-9]{32}$/) },
    { key: 'DB_USER', value: 'wp' },
  ])
  await deploy
  await expect(page).toHaveURL(/\/environments\/a\/deployments\/s1$/)
})

test('a taken name is shown on the name field', async ({ page, api }) => {
  mockCommon(api)
  api.mock('POST', BASE, { status: 409, body: { code: 'DEPLOYMENT_NAME_TAKEN', message: 'taken' } })

  await openPicker(page)
  await page.getByRole('listitem').filter({ hasText: 'WordPress' }).getByRole('button', { name: 'Install' }).click()
  const dialog = page.getByRole('dialog', { name: 'Install WordPress' })
  await dialog.getByRole('button', { name: 'Install', exact: true }).click()
  await expect(dialog.getByLabel('Name', { exact: true })).toHaveAccessibleDescription(/already exists/)
  await expect(page).toHaveURL(/\/deployments$/)
})

test('Advanced opens the wizard with the chosen name, port and variables', async ({ page, api }) => {
  mockCommon(api)

  await openPicker(page)
  await page.getByRole('listitem').filter({ hasText: 'WordPress' }).getByRole('button', { name: 'Install' }).click()
  const dialog = page.getByRole('dialog', { name: 'Install WordPress' })
  await dialog.getByLabel('Name', { exact: true }).fill('blog')
  await dialog.getByLabel('Host port for wp (container port 80)').fill('9000')
  await dialog.getByRole('button', { name: 'Advanced' }).click()

  await expect(page.getByRole('dialog', { name: 'Install WordPress' })).toHaveCount(0)
  await expect(page.getByRole('radio', { name: 'Form wizard' })).toBeChecked()
  await expect(page.getByLabel('Name', { exact: true })).toHaveValue('blog')
  await expect(page.getByLabel('Service 1 image')).toHaveValue('wordpress:6')
  await expect(page.getByLabel('Service 1 port 1 host')).toHaveValue('9000')
})
