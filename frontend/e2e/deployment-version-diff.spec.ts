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
  status: 'RUNNING',
  source: 'FILE',
  lastDeployedAt: '2026-10-02T10:00:00Z',
  createdAt: '2026-10-01T10:00:00Z',
  yaml: 'services:\n  web:\n    image: nginx:3',
  envKeys: ['KEEP', 'NEW'],
}
const base = { source: 'MANUAL', commitSha: null, createdBy: 'ann' }
const versions = [
  { ...base, id: 'v3', createdAt: '2026-10-03T10:00:00Z', current: false, deployed: false },
  { ...base, id: 'v2', createdAt: '2026-10-02T10:00:00Z', current: true, deployed: true },
  { ...base, id: 'v1', createdAt: '2026-10-01T10:00:00Z', current: false, deployed: true },
]
const yaml = { v1: 'services:\n  web:\n    image: nginx:1', v2: 'services:\n  web:\n    image: nginx:2', v3: 'services:\n  web:\n    image: nginx:3' }

function mockPage(api: import('./fixtures/api.ts').ApiMock) {
  api.mock('GET', '/api/auth/me', { body: { username: 'u', role: 'OPERATOR', setupRequired: false } })
  api.mock('GET', '/api/hosts/overview', { body: [host] })
  api.mock('GET', '/api/hosts/a/deployments', { body: [] })
  api.mock('GET', `${BASE}/s1`, { body: deployment })
  api.mock('GET', `${BASE}/s1/versions`, { body: versions })
  api.mock('GET', `${BASE}/s1/files`, { body: [] })
  api.mock('GET', `${BASE}/s1/versions/v2`, {
    body: { ...versions[1], yaml: yaml.v2, baselineVersionId: 'v2', env: [{ key: 'KEEP', changed: false }, { key: 'GONE', changed: false }], removedKeys: [] },
  })
  api.mock('GET', `${BASE}/s1/versions/v1`, {
    body: { ...versions[2], yaml: yaml.v1, baselineVersionId: 'v2', env: [{ key: 'KEEP', changed: true }], removedKeys: ['GONE'] },
  })
  api.mock('GET', `${BASE}/s1/versions/v3`, {
    body: { ...versions[0], yaml: yaml.v3, baselineVersionId: 'v2', env: [{ key: 'KEEP', changed: false }, { key: 'NEW', changed: true }], removedKeys: ['GONE'] },
  })
}

test('rollback dialog shows the YAML diff and env key changes', async ({ page, api }) => {
  mockPage(api)
  api.mock('POST', `${BASE}/s1/versions/v1/rollback`, { status: 202, body: { runId: 'r1', versionId: 'v1' } })
  await page.goto('/environments/a/deployments/s1')
  await page.getByRole('button', { name: /Roll back to/ }).click()
  const dialog = page.getByRole('dialog', { name: 'Roll back deployment' })
  await expect(dialog.getByText('Environment variables')).toBeVisible()
  await expect(dialog.getByText('Removed')).toBeVisible()
  await expect(dialog.getByText('GONE')).toBeVisible()
  await expect(dialog.getByText('Changed')).toBeVisible()
  await expect(dialog.getByLabel('Compose file changes').first()).toBeVisible()
  await dialog.getByRole('button', { name: 'Roll back' }).click()
  await expect(page.getByLabel('Deployment output')).toContainText('Rollback')
})

test('deploy with a draft shows the diff first, narrow screens too', async ({ page, api }) => {
  mockPage(api)
  api.mock('POST', `${BASE}/s1/deploy`, { status: 202, body: { runId: 'd1', versionId: 'v3' } })
  await page.setViewportSize({ width: 390, height: 800 })
  await page.goto('/environments/a/deployments/s1')
  await page.getByRole('button', { name: 'Deploy', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: 'Deploy changes' })
  await expect(dialog.getByText('NEW')).toBeVisible()
  await expect(dialog.getByText('Added')).toBeVisible()
  await dialog.getByRole('button', { name: 'Deploy' }).click()
  await expect(page.getByLabel('Deployment output')).toBeVisible()
})
