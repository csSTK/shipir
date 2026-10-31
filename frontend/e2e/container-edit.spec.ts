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
  containers: { running: 1, stopped: 0, healthy: 0, unhealthy: 0 },
  images: 0,
  volumes: 0,
}
const details = {
  id: 'c1',
  name: 'web',
  image: 'nginx:1',
  state: 'running',
  created: '2026-10-01T10:00:00Z',
  startedAt: '2026-10-01T10:00:05Z',
  restartCount: 0,
  restartPolicy: { name: 'always', maxRetries: 0 },
  command: ['/docker-entrypoint.sh', 'nginx', '-g', 'daemon off;'],
  cmd: ['nginx', '-g', 'daemon off;'],
  privileged: false,
  hostNetwork: false,
  env: [{ key: 'MODE', value: 'prod' }],
  imageEnv: [],
  mounts: [
    { type: 'volume', name: 'data', source: '/var/lib/docker/volumes/data', destination: '/data', readOnly: false },
    { type: 'bind', name: null, source: '', destination: '/run/tmp', readOnly: false },
  ],
  labels: {},
  networks: [{ name: 'bridge', ipAddress: '172.17.0.2', gateway: '172.17.0.1', macAddress: '02:42:ac', aliases: [] }],
  ports: [{ containerPort: 80, protocol: 'tcp', hostIp: '0.0.0.0', hostPort: 8080 }],
  health: null,
  composeProject: null,
  composeService: null,
  memoryBytes: null,
  cpus: null,
}
const as = (role: string) => ({ body: { username: 'u', role, setupRequired: false } })

test.beforeEach(({ api }) => {
  api.mock('GET', '/api/hosts/overview', { body: [host] })
  api.mock('GET', '/api/hosts/a/containers', { body: [] })
  api.mock('GET', '/api/hosts/a/containers/c1', { body: details })
  api.mock('GET', '/api/hosts/a/containers/c2', { body: { ...details, id: 'c2' } })
  api.mock('GET', '/api/hosts/a/images', { body: [] })
  api.mock('GET', '/api/hosts/a/deployments', { body: [] })
})

test('operator edits a container: prefilled, confirms the recreate and lands on the new container', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', as('OPERATOR'))
  api.mock('POST', '/api/hosts/a/containers/c1/recreate', { body: { id: 'c2', name: 'web' } })
  await page.goto('/environments/a/containers/c1')
  await page.getByRole('button', { name: 'Edit', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: 'Edit container' })
  await expect(dialog.getByLabel('Image', { exact: true })).toHaveValue('nginx:1')
  await expect(dialog.getByLabel('Name', { exact: true })).toBeDisabled()
  await expect(dialog.getByLabel('Mount 1 source')).toHaveValue('data')
  await expect(dialog.getByLabel('Mount 2 source')).toHaveCount(0)
  await expect(dialog.getByLabel('Command (optional)')).toHaveValue('nginx -g "daemon off;"')
  await dialog.getByLabel('Image', { exact: true }).fill('nginx:2')
  await dialog.getByRole('button', { name: 'Review changes' }).click()
  await expect(dialog.getByText(/brief downtime/)).toBeVisible()
  const request = page.waitForRequest((r) => r.method() === 'POST' && r.url().endsWith('/containers/c1/recreate'))
  await dialog.getByRole('button', { name: 'Recreate container' }).click()
  const body = (await request).postDataJSON()
  expect(body).toMatchObject({ image: 'nginx:2', name: 'web', restartPolicy: 'always' })
  expect(body.command).toEqual(['nginx', '-g', 'daemon off;'])
  await expect(page).toHaveURL(/\/environments\/a\/containers\/c2$/)
})

test('duplicate needs a new name and has no fixed host port', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', as('OPERATOR'))
  api.mock('POST', '/api/hosts/a/containers/c1/duplicate', { status: 201, body: { id: 'c2', name: 'web-2' } })
  await page.goto('/environments/a/containers/c1')
  await page.getByRole('button', { name: 'Duplicate', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: 'Duplicate container' })
  await expect(dialog.getByLabel('Port 1 host port')).toHaveValue('')
  await dialog.getByRole('button', { name: 'Duplicate' }).click()
  await expect(dialog.getByText('Enter a name for the copy.')).toBeVisible()
  await dialog.getByLabel('Name', { exact: true }).fill('web-2')
  await dialog.getByRole('button', { name: 'Duplicate' }).click()
  await expect(page).toHaveURL(/\/environments\/a\/containers\/c2$/)
})

test('resource limits are sent as bytes, CPUs and policy', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', as('OPERATOR'))
  api.mock('PATCH', '/api/hosts/a/containers/c1/resources', { status: 204 })
  await page.goto('/environments/a/containers/c1')
  await page.getByRole('button', { name: 'Resource limits' }).click()
  const dialog = page.getByRole('dialog', { name: 'Resource limits' })
  await dialog.getByLabel('Memory limit (MiB)').fill('256')
  await dialog.getByLabel('CPUs').fill('1.5')
  const request = page.waitForRequest((r) => r.method() === 'PATCH')
  await dialog.getByRole('button', { name: 'Apply limits' }).click()
  expect((await request).postDataJSON()).toEqual({ memoryBytes: 256 * 1024 * 1024, cpus: 1.5, restartPolicy: null })
  await expect(dialog).toHaveCount(0)
})

test('resource limits dialog starts with the current limits', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', as('OPERATOR'))
  api.mock('GET', '/api/hosts/a/containers/c1', { body: { ...details, memoryBytes: 512 * 1024 * 1024, cpus: 1.5 } })
  await page.goto('/environments/a/containers/c1')
  await page.getByRole('button', { name: 'Resource limits' }).click()
  const dialog = page.getByRole('dialog', { name: 'Resource limits' })
  await expect(dialog.getByLabel('Memory limit (MiB)')).toHaveValue('512')
  await expect(dialog.getByLabel('CPUs')).toHaveValue('1.5')
})

test('viewers get no change actions; compose-managed containers show why they are disabled', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', as('VIEWER'))
  await page.goto('/environments/a/containers/c1')
  await expect(page.getByRole('heading', { name: 'web' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Edit', exact: true })).toHaveCount(0)
  api.mock('GET', '/api/auth/me', as('ADMIN'))
  api.mock('GET', '/api/hosts/a/containers/c1', { body: { ...details, composeProject: 'shop', composeService: 'web' } })
  await page.goto('/environments/a/containers/c1')
  await expect(page.getByRole('button', { name: 'Edit', exact: true })).toBeDisabled()
  await expect(page.getByText(/managed by the deployment shop/)).toBeVisible()
})
