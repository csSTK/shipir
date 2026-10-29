import type { Page } from '@playwright/test'
import { expect, mockDeploymentCards, test } from './fixtures/api.ts'

const BASE = '/api/hosts/a/managed-deployments'
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
const deployment = {
  id: 's1',
  name: 'shop',
  status: 'STOPPED',
  source: 'FILE',
  lastDeployedAt: null,
  createdAt: '2026-10-01T10:00:00Z',
  yaml: 'services:\n  web:\n    image: nginx',
  envKeys: [],
}
const entry = { directory: false, size: 4, modifiedAt: '2026-10-01T10:00:00Z', readOnly: false }
const file = { path: 'app.conf', content: 'a=1\n', size: 4, modifiedAt: '2026-10-01T10:00:00Z', readOnly: false }

function mockCommon(api: import('./fixtures/api.ts').ApiMock, role: string) {
  api.mock('GET', '/api/auth/me', { body: { username: 'u', role, setupRequired: false } })
  api.mock('GET', '/api/hosts/overview', { body: [host] })
  api.mock('GET', '/api/hosts/a/deployments', { body: [] })
  api.mock('GET', BASE, { body: [] })
  api.mock('GET', `${BASE}/s1`, { body: deployment })
  mockDeploymentCards(api, `${BASE}/s1`)
}

/** The files endpoints share one path (tree vs. file by query), which the shared mock cannot tell apart: a small stateful stub. */
async function stubFiles(page: Page, opts: { putStatus?: number; putBody?: unknown; envFile?: boolean } = {}) {
  const state = { content: 'a=1\n', backups: [] as { id: string; createdAt: string; size: number }[], restored: [] as unknown[] }
  await page.route(
    (url) => url.pathname.startsWith(`${BASE}/s1/files`),
    async (route) => {
      const req = route.request()
      const url = new URL(req.url())
      const sub = url.pathname.slice(`${BASE}/s1/files`.length)
      const m = req.method()
      if (sub === '' && m === 'GET' && !url.searchParams.has('path')) {
        const tree = [{ ...entry, path: 'app.conf' }]
        if (opts.envFile) tree.push({ ...entry, path: 'db.env' })
        return route.fulfill({ json: tree })
      }
      if (sub === '' && m === 'GET' && url.searchParams.get('path') === 'db.env') {
        return route.fulfill({ status: 403, json: { code: 'ENV_FILE_RESTRICTED', message: 'restricted' } })
      }
      if (sub === '' && m === 'GET') return route.fulfill({ json: { ...file, content: state.content, modifiedAt: String(state.backups.length) } })
      if (sub === '' && m === 'PUT') {
        if (opts.putStatus) return route.fulfill({ status: opts.putStatus, json: opts.putBody })
        state.backups = [{ id: 'b1', createdAt: '2026-10-02T10:00:00Z', size: 4 }]
        state.content = JSON.parse(req.postData() ?? '{}').content
        return route.fulfill({ json: { ...file, content: state.content } })
      }
      if (sub === '/backups') return route.fulfill({ json: state.backups })
      if (sub === '/restore') {
        state.restored.push(JSON.parse(req.postData() ?? '{}'))
        state.content = 'a=1\n'
        return route.fulfill({ json: file })
      }
      return route.fulfill({ status: 501, json: {} })
    },
  )
  return state
}

test('operator edits a file, sees the backup and restores it', async ({ page, api }) => {
  mockCommon(api, 'OPERATOR')
  const state = await stubFiles(page)

  await page.goto('/environments/a/deployments/s1')
  await page.getByRole('button', { name: 'app.conf' }).click()
  await expect(page.getByText('No backups yet')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Save' })).toBeDisabled()

  // The editor is Monaco: type into it.
  await page.locator('.cf-editor .monaco-editor').first().click()
  await page.keyboard.press('ControlOrMeta+End')
  await page.keyboard.type('b=3')
  await expect(page.getByText('Unsaved changes')).toBeVisible()

  await page.getByRole('button', { name: 'Save' }).click()
  await expect(page.getByRole('list', { name: 'Backups' }).getByRole('listitem')).toHaveCount(1)
  expect(state.content).toContain('b=3')

  await page.getByRole('button', { name: /Restore backup from/ }).click()
  await page.getByRole('dialog').getByRole('button', { name: 'Restore' }).click()
  await expect(page.getByRole('dialog')).toBeHidden()
  expect(state.restored).toEqual([{ backupId: 'b1' }])
})

test('server path error is shown inline when creating a file', async ({ page, api }) => {
  mockCommon(api, 'OPERATOR')
  await stubFiles(page, { putStatus: 400, putBody: { code: 'INVALID_PATH', message: 'bad' } })

  await page.goto('/environments/a/deployments/s1')
  await page.getByRole('button', { name: 'New file' }).click()
  await page.getByLabel('File path').fill('../x')
  await page.getByRole('button', { name: 'Create' }).click()
  await expect(page.getByRole('alert')).toContainText('This path is not valid')
})

test('viewer cannot edit', async ({ page, api }) => {
  mockCommon(api, 'VIEWER')
  await stubFiles(page)

  await page.goto('/environments/a/deployments/s1')
  await page.getByRole('button', { name: 'app.conf' }).click()
  await expect(page.getByRole('list', { name: 'Backups' })).toHaveCount(0)
  await expect(page.getByText('No backups yet')).toBeVisible()
  await expect(page.getByRole('button', { name: 'New file' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Save' })).toHaveCount(0)
})

test('viewer opening an env-like file sees why it is restricted', async ({ page, api }) => {
  mockCommon(api, 'VIEWER')
  await stubFiles(page, { envFile: true })

  await page.goto('/environments/a/deployments/s1')
  await page.getByRole('button', { name: 'db.env' }).click()
  // Monaco renders its own role="alert" nodes, so pick the panel's message by its text.
  await expect(page.getByRole('alert').filter({ hasText: 'operate access' })).toBeVisible()
  await expect(page.getByText('You are not allowed to do this.')).toHaveCount(0)
})
