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
const BASE = '/api/hosts/a/managed-stacks'
const stack = {
  id: 's1',
  name: 'shop',
  status: 'STOPPED',
  source: 'FILE',
  lastDeployedAt: null,
  createdAt: '2026-10-01T10:00:00Z',
  yaml: 'services:\n  web:\n    image: nginx',
  envKeys: ['TOKEN'],
}
const versions = [
  { id: 'v2', source: 'MANUAL', commitSha: null, createdBy: 'ann', createdAt: '2026-10-02T10:00:00Z', current: true, deployed: true },
  { id: 'v1', source: 'MANUAL', commitSha: null, createdBy: 'ann', createdAt: '2026-10-01T10:00:00Z', current: false, deployed: true },
]

function mockCommon(api: import('./fixtures/api.ts').ApiMock, role: string) {
  api.mock('GET', '/api/auth/me', { body: { username: 'u', role, setupRequired: false } })
  api.mock('GET', '/api/hosts/overview', { body: [host] })
  api.mock('GET', '/api/hosts/a/stacks', { body: [] })
  api.mock('GET', BASE, { body: [] })
  api.mock('GET', `${BASE}/s1`, { body: stack })
  api.mock('GET', `${BASE}/s1/versions`, { body: versions })
}

test('operator creates, deploys, rolls back and deletes a stack', async ({ page, api }) => {
  mockCommon(api, 'OPERATOR')
  api.mock('POST', BASE, { status: 201, body: stack })
  api.mock('POST', `${BASE}/s1/deploy`, { status: 202, body: { deploymentId: 'dep-1', versionId: 'v2' } })
  api.mock('POST', `${BASE}/s1/versions/v1/rollback`, { status: 202, body: { deploymentId: 'dep-2', versionId: 'v1' } })
  api.mock('DELETE', `${BASE}/s1`, { status: 204 })

  await page.goto('/environments/a/stacks')
  await page.getByRole('button', { name: 'Add stack' }).click()
  await page.getByLabel('Name', { exact: true }).fill('shop')
  await page.getByLabel('Compose file').fill('services:\n  web:\n    image: nginx')
  await page.getByRole('button', { name: 'Create stack' }).click()

  await expect(page).toHaveURL(/\/environments\/a\/stacks\/s1$/)
  await expect(page.getByRole('heading', { name: 'shop' })).toBeVisible()

  await page.getByRole('button', { name: 'Deploy' }).click()
  await expect(page.getByRole('status')).toContainText('dep-1')

  await page.getByRole('button', { name: /Roll back to/ }).click()
  await page.getByRole('dialog').getByRole('button', { name: 'Roll back' }).click()
  await expect(page.getByRole('status')).toContainText('Rollback started')
  await expect(page.getByRole('status')).toContainText('dep-2')

  await page.getByRole('button', { name: 'Delete' }).click()
  await page.getByRole('dialog').getByRole('button', { name: 'Delete' }).click()
  await expect(page).toHaveURL(/\/environments\/a\/stacks$/)
})

test('viewer sees the stack read-only', async ({ page, api }) => {
  mockCommon(api, 'VIEWER')
  await page.goto('/environments/a/stacks/s1')
  await expect(page.getByRole('heading', { name: 'shop' })).toBeVisible()
  await expect(page.getByRole('list', { name: 'Versions' })).toBeVisible()
  for (const name of ['Deploy', 'Stop', 'Delete']) {
    await expect(page.getByRole('button', { name, exact: true })).toHaveCount(0)
  }
  await expect(page.getByRole('button', { name: /Roll back/ })).toHaveCount(0)
})

test('viewer does not get Add stack', async ({ page, api }) => {
  mockCommon(api, 'VIEWER')
  await page.goto('/environments/a/stacks')
  await expect(page.getByRole('heading', { name: 'Stacks' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Add stack' })).toHaveCount(0)
})
