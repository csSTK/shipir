import type { Page } from '@playwright/test'
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
  images: 1,
  volumes: 0,
}
const images = [
  { id: 'sha256:aaaaaaaaaaaaaaaaaaaa', repoTags: ['nginx:latest', 'nginx:1.27'], size: 1000, created: '2026-10-01T10:00:00Z', inUse: false },
]

const stompFrame = (command: string, headers: Record<string, string>, body = '') =>
  `${command}\n${Object.entries(headers).map(([k, v]) => `${k}:${v}`).join('\n')}\n\n${body}\0`

async function mockBroker(page: Page, lines: object[]) {
  await page.routeWebSocket('**/ws', (ws) => {
    ws.onMessage((raw) => {
      const text = String(raw)
      if (text.startsWith('CONNECT') || text.startsWith('STOMP')) {
        ws.send(stompFrame('CONNECTED', { version: '1.2', 'heart-beat': '0,0' }))
      } else if (text.startsWith('SUBSCRIBE') && text.includes('/topic/deployments/')) {
        const id = /id:(.*)/.exec(text)?.[1] ?? '0'
        for (const l of lines) {
          ws.send(stompFrame('MESSAGE', { subscription: id, destination: '/topic/deployments/run-1', 'message-id': String(Math.random()) }, JSON.stringify(l)))
        }
      }
    })
  })
}

function mockCommon(api: ApiMock, role: string) {
  api.mock('GET', '/api/auth/me', { body: { username: 'u', role, setupRequired: false } })
  api.mock('GET', '/api/hosts/overview', { body: [host] })
  api.mock('GET', '/api/hosts/a/images', { body: images })
  api.mock('GET', '/api/hosts/a/containers', { body: [] })
  api.mock('GET', '/api/hosts/a/deployments', { body: [] })
}

async function fillUpload(page: Page, tag = 'myapp:1.0') {
  await page.getByRole('button', { name: 'Build image' }).click()
  const dialog = page.getByRole('dialog', { name: 'Build image' })
  await dialog.getByLabel('Build context').setInputFiles({ name: 'ctx.tar', mimeType: 'application/x-tar', buffer: Buffer.from('tar') })
  await dialog.getByLabel('Image tag').fill(tag)
  return dialog
}

test('admin builds an uploaded context and follows the output', async ({ page, api }) => {
  mockCommon(api, 'ADMIN')
  api.mock('POST', '/api/hosts/a/images/build', { status: 202, body: { runId: 'run-1' } })
  await mockBroker(page, [
    { seq: 1, type: 'LINE', line: 'Step 1/2 : FROM busybox' },
    { seq: 2, type: 'STATUS', status: 'DONE' },
  ])
  await page.goto('/environments/a/images')
  const dialog = await fillUpload(page)
  await dialog.getByRole('button', { name: 'Add build argument' }).click()
  await dialog.getByRole('textbox', { name: 'Build argument 1' }).fill('NODE_ENV=prod')
  await expect(dialog.getByText(/visible in the image history/i)).toBeVisible()
  await dialog.getByRole('button', { name: 'Build', exact: true }).click()
  await expect(dialog.getByRole('log', { name: 'Output lines' })).toContainText('Step 1/2 : FROM busybox')
  await expect(dialog.getByRole('status').filter({ hasText: 'Succeeded' })).toBeVisible()
})

test('a running build (409) and an oversized context (413) are shown inline', async ({ page, api }) => {
  mockCommon(api, 'ADMIN')
  api.mock('POST', '/api/hosts/a/images/build', { status: 409, body: { code: 'BUILD_IN_PROGRESS', message: 'x' } })
  await page.goto('/environments/a/images')
  const dialog = await fillUpload(page)
  await dialog.getByRole('button', { name: 'Build', exact: true }).click()
  await expect(dialog.getByRole('alert')).toContainText('already running')
  api.mock('POST', '/api/hosts/a/images/build', { status: 413, body: { code: 'SIZE_LIMIT_EXCEEDED', message: 'x' } })
  await dialog.getByRole('button', { name: 'Build', exact: true }).click()
  await expect(dialog.getByRole('alert')).toContainText('200 MiB')
})

test('operator tags an image', async ({ page, api }) => {
  mockCommon(api, 'OPERATOR')
  api.mock('POST', '/api/hosts/a/images/sha256%3Aaaaaaaaaaaaaaaaaaaaa/tag', { status: 204 })
  await page.goto('/environments/a/images')
  await page.getByRole('button', { name: 'More actions for nginx:latest' }).click()
  await page.getByRole('menuitem', { name: 'Tag…' }).click()
  const dialog = page.getByRole('dialog', { name: 'Tag image' })
  await expect(dialog.getByLabel('Repository')).toHaveValue('nginx')
  await dialog.getByLabel('Repository').fill('registry.example.com/team/nginx')
  const request = page.waitForRequest((r) => r.method() === 'POST' && r.url().endsWith('/tag'))
  await dialog.getByRole('button', { name: 'Tag', exact: true }).click()
  expect((await request).postDataJSON()).toEqual({ repository: 'registry.example.com/team/nginx', tag: 'latest' })
  await expect(dialog).toBeHidden()
})

test('operator pushes a tag and sees the live output', async ({ page, api }) => {
  mockCommon(api, 'OPERATOR')
  api.mock('POST', '/api/hosts/a/images/push', { status: 202, body: { runId: 'run-1' } })
  await mockBroker(page, [
    { seq: 1, type: 'LINE', line: 'Push failed (PUSH_FAILED)' },
    { seq: 2, type: 'STATUS', status: 'FAILED' },
  ])
  await page.goto('/environments/a/images')
  await page.getByRole('button', { name: 'More actions for nginx:latest' }).click()
  await page.getByRole('menuitem', { name: 'Push…' }).click()
  const dialog = page.getByRole('dialog', { name: 'Push image' })
  await dialog.getByLabel('Reference to push').selectOption('nginx:1.27')
  const request = page.waitForRequest((r) => r.method() === 'POST' && r.url().endsWith('/images/push'))
  await dialog.getByRole('button', { name: 'Push', exact: true }).click()
  expect((await request).postDataJSON()).toEqual({ reference: 'nginx:1.27' })
  await expect(dialog.getByRole('log', { name: 'Output lines' })).toContainText('Push failed (PUSH_FAILED)')
  await expect(dialog.getByRole('status').filter({ hasText: 'Failed' })).toBeVisible()
})

test('operator has no Build button', async ({ page, api }) => {
  mockCommon(api, 'OPERATOR')
  await page.goto('/environments/a/images')
  await expect(page.getByRole('button', { name: 'Pull image' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Build image' })).toHaveCount(0)
})
