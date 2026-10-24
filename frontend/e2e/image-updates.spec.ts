import type { Page } from '@playwright/test'
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
  images: 2,
  volumes: 0,
}
const images = [
  { id: 'sha256:aaaaaaaaaaaaaaaaaaaa', repoTags: ['nginx:latest'], size: 1000, created: '2026-10-01T10:00:00Z', inUse: true, updateAvailable: true },
  { id: 'sha256:bbbbbbbbbbbbbbbbbbbb', repoTags: ['redis:7'], size: 1000, created: '2026-09-01T10:00:00Z', inUse: false, updateAvailable: false },
]
const containers = [
  { id: 'c1', name: 'web', image: 'nginx:latest', state: 'running', status: 'Up', health: null, created: '2026-10-01T10:00:00Z', ports: [], composeProject: null, composeService: null },
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

function mockCommon(api: import('./fixtures/api.ts').ApiMock, role: string) {
  api.mock('GET', '/api/auth/me', { body: { username: 'u', role, setupRequired: false } })
  api.mock('GET', '/api/hosts/overview', { body: [host] })
  api.mock('GET', '/api/hosts/a/images', { body: images })
  api.mock('GET', '/api/hosts/a/containers', { body: containers })
  api.mock('GET', '/api/hosts/a/deployments', { body: [] })
}

test('operator pulls an update from the badge and sees live progress', async ({ page, api }) => {
  mockCommon(api, 'OPERATOR')
  api.mock('POST', '/api/hosts/a/images/pull', { status: 202, body: { runId: 'run-1' } })
  await mockBroker(page, [
    { seq: 1, type: 'LINE', line: 'Pulling nginx:latest' },
    { seq: 2, type: 'STATUS', status: 'DONE' },
  ])
  await page.goto('/environments/a/images')
  await expect(page.getByText('Update available')).toHaveCount(1)
  await page.getByRole('button', { name: 'Pull & update nginx:latest' }).click()
  const dialog = page.getByRole('dialog', { name: 'Pull image' })
  await expect(dialog.getByLabel('Image reference')).toHaveValue('nginx:latest')
  await dialog.getByRole('button', { name: 'Pull', exact: true }).click()
  await expect(dialog.getByRole('log', { name: 'Output lines' })).toContainText('Pulling nginx:latest')
  await expect(dialog.getByRole('status').filter({ hasText: 'Succeeded' })).toBeVisible()
})

test('invalid reference is shown inline', async ({ page, api }) => {
  mockCommon(api, 'ADMIN')
  api.mock('POST', '/api/hosts/a/images/pull', { status: 400, body: { code: 'INVALID_IMAGE_REFERENCE', message: 'bad' } })
  await page.goto('/environments/a/images')
  await page.getByRole('button', { name: 'Pull image' }).click()
  await page.getByLabel('Image reference').fill('Not Valid')
  await page.getByRole('button', { name: 'Pull', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('not a valid image reference')
})

test('viewer sees the badge without pull controls; containers show it too', async ({ page, api }) => {
  mockCommon(api, 'VIEWER')
  await page.goto('/environments/a/images')
  await expect(page.getByText('Update available')).toHaveCount(1)
  await expect(page.getByRole('button', { name: /Pull/ })).toHaveCount(0)
  await page.goto('/environments/a/containers')
  await expect(page.getByRole('link', { name: 'Update available for web' })).toBeVisible()
})
