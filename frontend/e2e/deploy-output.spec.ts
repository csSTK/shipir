import type { Page, WebSocketRoute } from '@playwright/test'
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
  envKeys: [],
}

const stompFrame = (command: string, headers: Record<string, string>, body = '') =>
  `${command}\n${Object.entries(headers).map(([k, v]) => `${k}:${v}`).join('\n')}\n\n${body}\0`

/** Minimal STOMP broker: answers CONNECT, and on SUBSCRIBE hands the connection to `onSubscribe`. */
async function mockBroker(
  page: Page,
  onSubscribe: (send: (body: object) => void, ws: WebSocketRoute, attempt: number) => void,
) {
  const connectHeaders: string[] = []
  let attempt = 0
  await page.routeWebSocket('**/ws', (ws) => {
    ws.onMessage((raw) => {
      const text = String(raw)
      if (text.startsWith('CONNECT') || text.startsWith('STOMP')) {
        connectHeaders.push(text)
        ws.send(stompFrame('CONNECTED', { version: '1.2', 'heart-beat': '0,0' }))
      } else if (text.startsWith('SUBSCRIBE')) {
        const id = /id:(.*)/.exec(text)?.[1] ?? '0'
        attempt += 1
        onSubscribe(
          (body) =>
            ws.send(
              stompFrame(
                'MESSAGE',
                { subscription: id, destination: '/topic/deployments/dep-1', 'message-id': String(Math.random()) },
                JSON.stringify(body),
              ),
            ),
          ws,
          attempt,
        )
      }
    })
  })
  return connectHeaders
}

async function openAndDeploy(page: Page, api: import('./fixtures/api.ts').ApiMock) {
  api.mock('GET', '/api/auth/me', { body: { username: 'u', role: 'OPERATOR', setupRequired: false } })
  api.mock('GET', '/api/hosts/overview', { body: [host] })
  api.mock('GET', '/api/hosts/a/stacks', { body: [] })
  api.mock('GET', BASE, { body: [] })
  api.mock('GET', `${BASE}/s1`, { body: stack })
  api.mock('GET', `${BASE}/s1/versions`, { body: [] })
  api.mock('POST', `${BASE}/s1/deploy`, { status: 202, body: { deploymentId: 'dep-1', versionId: 'v1' } })
  await page.goto('/environments/a/stacks/s1')
  await page.getByRole('button', { name: 'Deploy', exact: true }).click()
}

test('deploy streams output and ends with a success badge', async ({ page, api }) => {
  const headers = await mockBroker(page, (send) => {
    send({ seq: 1, type: 'LINE', line: 'Pulling web' })
    send({ seq: 2, type: 'LINE', line: 'Container shop-web-1 Started' })
    send({ seq: 3, type: 'STATUS', status: 'DONE' })
  })
  await openAndDeploy(page, api)

  const log = page.getByRole('log', { name: 'Output lines' })
  await expect(log).toContainText('Pulling web')
  await expect(log).toContainText('Container shop-web-1 Started')
  await expect(page.getByLabel('Deployment output').getByRole('status')).toHaveText('Succeeded')
  expect(headers[0]).toContain('X-XSRF-TOKEN')
})

test('a failed deployment shows the failed badge', async ({ page, api }) => {
  await mockBroker(page, (send) => {
    send({ seq: 1, type: 'LINE', line: 'pull access denied' })
    send({ seq: 2, type: 'STATUS', status: 'FAILED' })
  })
  await openAndDeploy(page, api)
  await expect(page.getByLabel('Deployment output').getByRole('status')).toHaveText('Failed')
})

test('scrolling up pauses autoscroll and Jump to latest resumes it', async ({ page, api }) => {
  await mockBroker(page, (send) => {
    for (let i = 1; i <= 200; i++) send({ seq: i, type: 'LINE', line: `line ${i}` })
  })
  await openAndDeploy(page, api)

  const log = page.getByRole('log', { name: 'Output lines' })
  await expect(log).toContainText('line 200')
  await expect(page.getByRole('button', { name: 'Jump to latest' })).toHaveCount(0)
  await log.evaluate((el) => (el.scrollTop = 0))
  await page.getByRole('button', { name: 'Jump to latest' }).click()
  await expect(page.getByRole('button', { name: 'Jump to latest' })).toHaveCount(0)
  await expect.poll(() => log.evaluate((el) => el.scrollHeight - el.scrollTop - el.clientHeight)).toBeLessThan(5)
})

test('shows reconnecting and does not duplicate replayed lines', async ({ page, api }) => {
  await mockBroker(page, (send, ws, attempt) => {
    send({ seq: 1, type: 'LINE', line: 'first' })
    if (attempt === 1) {
      void ws.close() // drop the connection; the client reconnects and the server replays
    } else {
      send({ seq: 2, type: 'LINE', line: 'second' })
      send({ seq: 3, type: 'STATUS', status: 'DONE' })
    }
  })
  await openAndDeploy(page, api)

  const out = page.getByLabel('Deployment output')
  await expect(out.getByRole('status')).toContainText('reconnecting')
  await expect(out.getByRole('status')).toHaveText('Succeeded', { timeout: 10_000 })
  await expect(page.getByRole('log').getByText('first', { exact: true })).toHaveCount(1)
  await expect(page.getByRole('log')).toContainText('second')
})
