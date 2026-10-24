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
const live = [{ name: 'blog', containerCount: 1, runningCount: 1, status: 'RUNNING', workingDir: '/srv/blog', managed: false, managedDeploymentId: null }]

function mockCommon(api: import('./fixtures/api.ts').ApiMock, role: string) {
  api.mock('GET', '/api/auth/me', { body: { username: 'u', role, setupRequired: false } })
  api.mock('GET', '/api/hosts/overview', { body: [host] })
  api.mock('GET', '/api/hosts/a/deployments', { body: live })
  api.mock('GET', '/api/hosts/a/managed-deployments', { body: [] })
}

test('operator adopts a project with the detected files and lands on its detail page', async ({ page, api }) => {
  mockCommon(api, 'OPERATOR')
  api.mock('POST', '/api/hosts/a/deployments/blog/adopt', { status: 201, body: { id: 'm1', name: 'blog', status: 'RUNNING', source: 'FILE' } })
  api.mock('GET', '/api/hosts/a/managed-deployments/m1', { status: 404, body: { code: 'DEPLOYMENT_NOT_FOUND', message: 'x' } })
  api.mock('GET', '/api/hosts/a/managed-deployments/m1/versions', { body: [] })
  await page.goto('/environments/a/deployments')
  await page.getByRole('button', { name: 'Adopt blog' }).click()
  const dialog = page.getByRole('dialog', { name: 'Adopt deployment' })
  await expect(dialog).toContainText('Running containers are not restarted')
  await dialog.getByRole('button', { name: 'Adopt' }).click()
  await expect(page).toHaveURL(/\/environments\/a\/deployments\/m1$/)
})

test('unreadable compose file asks for pasted YAML', async ({ page, api }) => {
  mockCommon(api, 'ADMIN')
  api.mock('POST', '/api/hosts/a/deployments/blog/adopt', { status: 400, body: { code: 'YAML_REQUIRED', message: 'x' } })
  await page.goto('/environments/a/deployments')
  await page.getByRole('button', { name: 'Adopt blog' }).click()
  const dialog = page.getByRole('dialog', { name: 'Adopt deployment' })
  await dialog.getByRole('button', { name: 'Adopt' }).click()
  await expect(dialog.getByRole('alert')).toContainText('Paste it below')
  await expect(dialog.getByText('Compose file', { exact: true })).toBeVisible()
})

test('viewer has no adopt action', async ({ page, api }) => {
  mockCommon(api, 'VIEWER')
  await page.goto('/environments/a/deployments')
  await expect(page.getByRole('table', { name: 'deployments' })).toContainText('blog')
  await expect(page.getByRole('button', { name: /^Adopt / })).toHaveCount(0)
})
