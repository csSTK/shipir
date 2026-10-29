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
const git = {
  repoUrl: 'https://github.com/acme/shop.git',
  ref: 'main',
  composePath: 'compose.yaml',
  credentialId: 'c1',
  lastCommitSha: 'abcdef1234567',
  autoUpdateEnabled: false,
  autoUpdateIntervalSeconds: 300,
  lastPolledAt: null,
  lastPollError: null,
}
const deployment = {
  id: 's1',
  name: 'shop',
  status: 'RUNNING',
  source: 'GIT',
  lastDeployedAt: '2026-10-02T10:00:00Z',
  createdAt: '2026-10-01T10:00:00Z',
  yaml: 'services:\n  web:\n    image: nginx',
  envKeys: [],
  git,
}
const versions = [
  { id: 'v1', source: 'GIT', commitSha: 'abcdef1234567', createdBy: null, createdAt: '2026-10-01T10:00:00Z', current: true, deployed: true },
]

function mockCommon(api: ApiMock, role: string) {
  api.mock('GET', '/api/auth/me', { body: { username: 'u', role, setupRequired: false } })
  api.mock('GET', '/api/hosts/overview', { body: [host] })
  api.mock('GET', '/api/hosts/a/deployments', { body: [] })
  api.mock('GET', BASE, { body: [] })
  api.mock('GET', `${BASE}/s1`, { body: deployment })
  mockDeploymentCards(api, `${BASE}/s1`, versions)
  api.mock('GET', '/api/git/credentials', {
    body: [{ id: 'c1', name: 'GitHub bot', username: 'bot', tokenHint: '…abcd', createdAt: '', updatedAt: '' }],
  })
}

async function fillRepo(page: import('@playwright/test').Page) {
  await page.getByRole('button', { name: 'Add deployment' }).click()
  await page.getByLabel('Name', { exact: true }).fill('shop')
  await page.getByRole('radio', { name: 'Git repository' }).check()
  await page.getByLabel('Repository URL').fill('https://github.com/acme/shop.git')
}

test('admin creates a deployment from a Git repository', async ({ page, api }) => {
  mockCommon(api, 'ADMIN')
  api.mock('POST', BASE, { status: 201, body: deployment })
  await page.goto('/environments/a/deployments')
  await fillRepo(page)
  await page.getByLabel('Credential').selectOption({ label: 'GitHub bot' })
  await page.getByRole('button', { name: 'Create deployment' }).click()
  await expect(page).toHaveURL(/\/environments\/a\/deployments\/s1$/)
  const panel = page.getByRole('region', { name: 'Git source' })
  await expect(panel).toContainText('https://github.com/acme/shop.git')
  await expect(panel).toContainText('abcdef1')
})

test('shows GIT_AUTH_FAILED inline and operators create public deployments without a credential list', async ({ page, api }) => {
  mockCommon(api, 'OPERATOR')
  api.mock('POST', BASE, { status: 422, body: { code: 'GIT_AUTH_FAILED', message: 'x' } })
  await page.goto('/environments/a/deployments')
  await fillRepo(page)
  await expect(page.getByLabel('Credential')).toHaveCount(0)
  await expect(page.getByText(/Only administrators can pick a credential/)).toBeVisible()
  await page.getByRole('button', { name: 'Create deployment' }).click()
  await expect(page.getByText(/rejected the credentials/)).toBeVisible()
  await expect(page.getByLabel('Repository URL')).toHaveValue('https://github.com/acme/shop.git')
})

test('Pull & redeploy shows the output and the new commit', async ({ page, api }) => {
  mockCommon(api, 'OPERATOR')
  api.mock('POST', `${BASE}/s1/git/redeploy`, { status: 202, body: { runId: 'dep-9', versionId: 'v2' } })
  await page.goto('/environments/a/deployments/s1')
  const panel = page.getByRole('region', { name: 'Git source' })
  await expect(panel).toContainText('abcdef1')
  api.mock('GET', `${BASE}/s1`, { body: { ...deployment, git: { ...git, lastCommitSha: '9999999aaaaaa' } } })
  await page.getByRole('button', { name: 'Pull & redeploy' }).click()
  await expect(page.getByLabel('Deployment output')).toContainText('dep-9')
  await expect(panel).toContainText('9999999')
})

test('saves auto-update settings', async ({ page, api }) => {
  mockCommon(api, 'OPERATOR')
  api.mock('PUT', `${BASE}/s1/git/auto-update`, { body: deployment })
  await page.goto('/environments/a/deployments/s1')
  const panel = page.getByRole('region', { name: 'Git source' })
  const request = page.waitForRequest((r) => r.method() === 'PUT' && r.url().endsWith('/git/auto-update'))
  await panel.getByRole('switch', { name: 'Auto-update' }).check()
  await panel.getByLabel('Check every (minutes)').fill('10')
  await panel.getByRole('button', { name: 'Save auto-update' }).click()
  expect((await request).postDataJSON()).toEqual({ enabled: true, intervalSeconds: 600 })
  await expect(panel.getByRole('status')).toContainText('Saved')
})
