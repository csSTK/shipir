import { expect, test } from './fixtures/api.ts'

const local = {
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
  images: 1,
  volumes: 0,
}
const as = (role: string) => ({ body: { username: 'u', role, setupRequired: false } })

test.beforeEach(({ api }) => {
  api.mock('GET', '/api/hosts/overview', { body: [local] })
  api.mock('GET', '/api/hosts/a/containers', { body: [] })
  api.mock('GET', '/api/hosts/a/images', { body: [] })
  api.mock('GET', '/api/hosts/a/deployments', { body: [] })
  api.mock('GET', '/api/hosts/a/networks', { body: [] })
  api.mock('GET', '/api/hosts/a/volumes', { body: [] })
  api.mock('GET', '/api/hosts/a/volume-backup-schedules', { body: [] })
})

test('operator creates a container; ADMIN-only options are absent', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', as('OPERATOR'))
  api.mock('POST', '/api/hosts/a/containers', { status: 201, body: { id: 'c1', name: 'web' } })
  await page.goto('/environments/a/containers')
  await page.getByRole('button', { name: 'Create container' }).click()
  const dialog = page.getByRole('dialog', { name: 'Create container' })
  await expect(dialog.getByLabel(/Privileged/)).toHaveCount(0)
  await dialog.getByRole('button', { name: 'Create' }).click()
  await expect(dialog.getByText('Enter an image, for example nginx:1.27.')).toBeVisible()
  await dialog.getByLabel('Image', { exact: true }).fill('nginx:1.27')
  await dialog.getByRole('button', { name: 'Add port' }).click()
  await dialog.getByLabel('Port 1 container port').fill('80')
  const request = page.waitForRequest((r) => r.method() === 'POST' && r.url().endsWith('/hosts/a/containers'))
  await dialog.getByRole('button', { name: 'Create' }).click()
  expect((await request).postDataJSON()).toMatchObject({ image: 'nginx:1.27', ports: [{ hostPort: null, containerPort: 80, protocol: 'tcp' }] })
  await expect(dialog).toHaveCount(0)
})

test('container server error stays inline', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', as('ADMIN'))
  api.mock('POST', '/api/hosts/a/containers', { status: 404, body: { code: 'RESOURCE_NOT_FOUND', message: 'image' } })
  await page.goto('/environments/a/containers')
  await page.getByRole('button', { name: 'Create container' }).click()
  const dialog = page.getByRole('dialog', { name: 'Create container' })
  await expect(dialog.getByLabel(/Privileged/)).toBeVisible()
  await dialog.getByLabel('Image', { exact: true }).fill('missing:1')
  await dialog.getByRole('button', { name: 'Create' }).click()
  await expect(dialog.getByRole('alert')).toContainText('Pull the image first')
})

test('operator creates a network and a volume, name conflict is inline', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', as('OPERATOR'))
  api.mock('POST', '/api/hosts/a/networks', { status: 201, body: { id: 'n1', name: 'backend' } })
  await page.goto('/environments/a/networks')
  await page.getByRole('button', { name: 'Create network' }).click()
  const net = page.getByRole('dialog', { name: 'Create network' })
  await net.getByLabel('Name', { exact: true }).fill('backend')
  await net.getByLabel('Subnet (optional)').fill('172.30.0.0/16')
  await net.getByRole('button', { name: 'Create' }).click()
  await expect(net).toHaveCount(0)

  api.mock('POST', '/api/hosts/a/volumes', { status: 409, body: { code: 'NAME_TAKEN', message: 'taken' } })
  await page.goto('/environments/a/volumes')
  await page.getByRole('button', { name: 'Create volume' }).click()
  const vol = page.getByRole('dialog', { name: 'Create volume' })
  await vol.getByLabel('Name', { exact: true }).fill('data')
  await vol.getByRole('button', { name: 'Create' }).click()
  await expect(vol.getByRole('alert')).toContainText('already exists')
})

test('viewer sees no create buttons', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', as('VIEWER'))
  await page.goto('/environments/a/volumes')
  await expect(page.getByRole('heading', { name: 'Volumes' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Create volume' })).toHaveCount(0)
})
