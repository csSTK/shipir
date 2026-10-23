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
const deployment = {
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
  api.mock('GET', '/api/hosts/a/deployments', { body: [] })
  api.mock('GET', BASE, { body: [] })
  api.mock('GET', `${BASE}/s1`, { body: deployment })
  api.mock('GET', `${BASE}/s1/versions`, { body: versions })
}

test('operator creates, deploys, rolls back and deletes a deployment', async ({ page, api }) => {
  mockCommon(api, 'OPERATOR')
  api.mock('POST', BASE, { status: 201, body: deployment })
  api.mock('POST', `${BASE}/s1/deploy`, { status: 202, body: { runId: 'dep-1', versionId: 'v2' } })
  api.mock('POST', `${BASE}/s1/versions/v1/rollback`, { status: 202, body: { runId: 'dep-2', versionId: 'v1' } })
  api.mock('DELETE', `${BASE}/s1`, { status: 204 })

  await page.goto('/environments/a/deployments')
  await page.getByRole('button', { name: 'Add deployment' }).click()
  await page.getByLabel('Name', { exact: true }).fill('shop')
  await page.getByLabel('Compose file').first().focus()
  await page.keyboard.insertText('services:\n  web:\n    image: nginx')
  await page.getByRole('button', { name: 'Create deployment' }).click()

  await expect(page).toHaveURL(/\/environments\/a\/deployments\/s1$/)
  await expect(page.getByRole('heading', { name: 'shop' })).toBeVisible()

  await page.getByRole('button', { name: 'Deploy' }).click()
  await expect(page.getByLabel('Deployment output')).toContainText('dep-1')

  await page.getByRole('button', { name: /Roll back to/ }).click()
  await page.getByRole('dialog').getByRole('button', { name: 'Roll back' }).click()
  await expect(page.getByLabel('Deployment output')).toContainText('Rollback')
  await expect(page.getByLabel('Deployment output')).toContainText('dep-2')

  await page.getByRole('button', { name: 'Delete' }).click()
  await page.getByRole('dialog').getByRole('button', { name: 'Delete' }).click()
  await expect(page).toHaveURL(/\/environments\/a\/deployments$/)
})

test('viewer sees the deployment read-only', async ({ page, api }) => {
  mockCommon(api, 'VIEWER')
  await page.goto('/environments/a/deployments/s1')
  await expect(page.getByRole('heading', { name: 'shop' })).toBeVisible()
  await expect(page.getByRole('list', { name: 'Versions' })).toBeVisible()
  for (const name of ['Deploy', 'Stop', 'Delete']) {
    await expect(page.getByRole('button', { name, exact: true })).toHaveCount(0)
  }
  await expect(page.getByRole('button', { name: /Roll back/ })).toHaveCount(0)
})

test('viewer does not get Add deployment', async ({ page, api }) => {
  mockCommon(api, 'VIEWER')
  await page.goto('/environments/a/deployments')
  await expect(page.getByRole('heading', { name: 'Deployments' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Add deployment' })).toHaveCount(0)
})

async function typeYaml(page: import('@playwright/test').Page, text: string) {
  const editor = page.getByLabel('Compose file').first()
  await editor.focus()
  await page.keyboard.press('ControlOrMeta+A')
  await page.keyboard.insertText(text)
}

test('operator creates a deployment with YAML and env, server error shows inline, then it saves', async ({ page, api }) => {
  mockCommon(api, 'OPERATOR')
  api.mock('POST', BASE, {
    status: 400,
    body: { code: 'INVALID_COMPOSE', message: 'invalid', details: ['services.web: image or build required'] },
  })

  await page.goto('/environments/a/deployments')
  await page.getByRole('button', { name: 'Add deployment' }).click()
  await page.getByLabel('Name', { exact: true }).fill('shop')
  await typeYaml(page, 'services:\n  web: {}')
  await page.getByRole('button', { name: 'Paste .env' }).click()
  await page.getByLabel('Paste the contents of a .env file').fill('TOKEN=abc\nMODE="a b"')
  await page.getByRole('button', { name: 'Add variables' }).click()
  await expect(page.getByLabel('Variable 2 value')).toHaveValue('a b')
  await expect(page.getByLabel('Variable 1 value')).toHaveAttribute('type', 'password')

  await page.getByRole('button', { name: 'Create deployment' }).click()
  await expect(page.getByText('services.web: image or build required')).toBeVisible()

  api.mock('POST', BASE, { status: 201, body: deployment })
  await page.getByRole('button', { name: 'Create deployment' }).click()
  await expect(page).toHaveURL(/\/environments\/a\/deployments\/s1$/)
})

test('operator edits the Compose file and variables of a deployment and saves a draft', async ({ page, api }) => {
  mockCommon(api, 'OPERATOR')
  api.mock('GET', `${BASE}/s1/env`, { body: { entries: [{ key: 'TOKEN', value: 'secret' }] } })
  api.mock('PUT', `${BASE}/s1`, { body: deployment })

  await page.goto('/environments/a/deployments/s1')
  await page.getByRole('button', { name: 'Edit', exact: true }).click()
  await typeYaml(page, 'services:\n  web:\n    image: nginx:1.27')
  await page.getByRole('button', { name: 'Edit variables' }).click()
  await expect(page.getByLabel('Variable 1 value')).toHaveAttribute('type', 'password')
  await page.getByRole('button', { name: 'Show values' }).click()
  await expect(page.getByLabel('Variable 1 value')).toHaveValue('secret')
  await page.getByRole('button', { name: 'Save' }).click()
  await expect(page.getByRole('status')).toContainText('Saved as a draft')
})

test('viewer has no Edit button', async ({ page, api }) => {
  mockCommon(api, 'VIEWER')
  await page.goto('/environments/a/deployments/s1')
  await expect(page.getByRole('heading', { name: 'shop' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Edit', exact: true })).toHaveCount(0)
})

test('old /stacks URLs redirect to /deployments', async ({ page, api }) => {
  mockCommon(api, 'OPERATOR')

  await page.goto('/environments/a/stacks/s1')
  await expect(page).toHaveURL(/\/environments\/a\/deployments\/s1$/)
  await expect(page.getByRole('heading', { name: 'shop' })).toBeVisible()

  await page.goto('/environments/a/stacks')
  await expect(page).toHaveURL(/\/environments\/a\/deployments$/)
})
