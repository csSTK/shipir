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
  containers: { running: 1, stopped: 1, healthy: 0, unhealthy: 0 },
  images: 1,
  volumes: 1,
}
const as = (role: string) => ({ body: { username: 'u', role, setupRequired: false } })
const base = { image: 'x', status: 'Up', health: null, created: '2026-10-01T10:00:00Z', ports: [], composeProject: null, composeService: null }
const containers = [
  { ...base, id: 'c1', name: 'web', state: 'running' },
  { ...base, id: 'c2', name: 'old', state: 'exited' },
  { ...base, id: 'c3', name: 'frozen', state: 'paused' },
]

test.beforeEach(({ api }) => {
  api.mock('GET', '/api/hosts/overview', { body: [local] })
  api.mock('GET', '/api/hosts/a/containers', { body: containers })
  api.mock('GET', '/api/hosts/a/deployments', { body: [] })
})

test('operator sees state-aware buttons and starts a container', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', as('OPERATOR'))
  api.mock('POST', '/api/hosts/a/containers/c2/start', { status: 204 })
  await page.goto('/environments/a/containers')
  await expect(page.getByRole('button', { name: 'Stop container web' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Start container web', exact: true })).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Unpause container frozen' })).toBeVisible()
  const request = page.waitForRequest((r) => r.method() === 'POST' && r.url().endsWith('/containers/c2/start'))
  await page.getByRole('button', { name: 'Start container old' }).click()
  await request
})

test('kill asks for confirmation', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', as('ADMIN'))
  api.mock('POST', '/api/hosts/a/containers/c1/kill', { status: 204 })
  await page.goto('/environments/a/containers')
  await page.getByRole('button', { name: 'Kill container web' }).click()
  const dialog = page.getByRole('dialog', { name: 'Kill container' })
  const request = page.waitForRequest((r) => r.method() === 'POST' && r.url().endsWith('/containers/c1/kill'))
  await dialog.getByRole('button', { name: 'Kill' }).click()
  await request
  await expect(dialog).toBeHidden()
})

test('state conflict shows an inline error', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', as('OPERATOR'))
  api.mock('POST', '/api/hosts/a/containers/c1/pause', {
    status: 409,
    body: { code: 'INVALID_CONTAINER_STATE', message: 'x' },
  })
  await page.goto('/environments/a/containers')
  await page.getByRole('button', { name: 'Pause container web' }).click()
  await expect(page.getByRole('alert')).toContainText('not in a state that allows this action')
})

test('viewer has no action buttons', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', as('VIEWER'))
  await page.goto('/environments/a/containers')
  await expect(page.getByRole('cell', { name: 'web' })).toBeVisible()
  await expect(page.getByRole('button', { name: /container web/ })).toHaveCount(0)
})
