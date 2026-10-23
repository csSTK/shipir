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
  images: 0,
  volumes: 0,
}
const container = {
  id: 'c1',
  name: 'web',
  image: 'nginx:1',
  state: 'running',
  status: 'Up',
  health: null,
  created: '2026-10-01T10:00:00Z',
  ports: [],
  composeProject: null,
  composeService: null,
}

const stompFrame = (command: string, headers: Record<string, string>, body = '') =>
  `${command}\n${Object.entries(headers).map(([k, v]) => `${k}:${v}`).join('\n')}\n\n${body}\0`

/** Minimal STOMP broker: records SUBSCRIBE headers and lets the test push frames. */
async function mockBroker(page: Page, onSubscribe: (send: (body: object) => void, headers: Record<string, string>) => void) {
  const subscribes: Record<string, string>[] = []
  await page.routeWebSocket('**/ws', (ws) => {
    ws.onMessage((raw) => {
      const text = String(raw)
      if (text.startsWith('CONNECT') || text.startsWith('STOMP')) {
        ws.send(stompFrame('CONNECTED', { version: '1.2', 'heart-beat': '0,0' }))
      } else if (text.startsWith('SUBSCRIBE')) {
        const headers = Object.fromEntries(
          text.split('\n').slice(1).map((l) => l.split(':')).filter((p) => p.length >= 2).map(([k, ...v]) => [k, v.join(':')]),
        )
        subscribes.push(headers)
        onSubscribe(
          (body) => ws.send(stompFrame('MESSAGE', { subscription: headers.id ?? '0', destination: headers.destination ?? '', 'message-id': String(Math.random()) }, JSON.stringify(body))),
          headers,
        )
      }
    })
  })
  return subscribes
}

test.beforeEach(({ api }) => {
  api.mock('GET', '/api/auth/me', { body: { username: 'u', role: 'VIEWER', setupRequired: false } })
  api.mock('GET', '/api/hosts/overview', { body: [host] })
  api.mock('GET', '/api/hosts/a/containers', { body: [container] })
  api.mock('GET', '/api/hosts/a/deployments', { body: [] })
})

test('opens the logs from the container row and streams lines, stderr marked', async ({ page }) => {
  const subs = await mockBroker(page, (send) => {
    send({ seq: 1, type: 'LINE', stream: 'STDOUT', line: 'listening on 80' })
    send({ seq: 2, type: 'LINE', stream: 'STDERR', line: 'warn: slow' })
  })
  await page.goto('/environments/a/containers')
  await page.getByRole('link', { name: 'Logs of web' }).click()
  await expect(page).toHaveURL(/\/environments\/a\/containers\/c1\/logs$/)
  const log = page.getByRole('log', { name: 'Container log' })
  await expect(log).toContainText('listening on 80')
  await expect(log).toContainText('warn: slow')
  await expect(log.locator('.clogs__line--err')).toHaveText('warn: slow')
  expect(subs[0].tail).toBe('200')
  expect(subs[0].timestamps).toBe('false')
})

test('changing tail and timestamps resubscribes with new headers and clears lines', async ({ page }) => {
  const subs = await mockBroker(page, (send, h) => {
    send({ seq: 1, type: 'LINE', stream: 'STDOUT', line: `tail ${h.tail} ts ${h.timestamps}`, ...(h.timestamps === 'true' ? { timestamp: '2026-10-23T10:00:00Z' } : {}) })
  })
  await page.goto('/environments/a/containers/c1/logs')
  const log = page.getByRole('log', { name: 'Container log' })
  await expect(log).toContainText('tail 200 ts false')
  await page.getByLabel('Show last').selectOption('500')
  await expect(log).toContainText('tail 500 ts false')
  await expect(log).not.toContainText('tail 200')
  await page.getByLabel('Timestamps').check()
  await expect(log).toContainText('2026-10-23T10:00:00Z')
  expect(subs.map((s) => `${s.tail}/${s.timestamps}`)).toEqual(['200/false', '500/false', '500/true'])
})

test('scrolling up pauses autoscroll and Jump to latest resumes it', async ({ page }) => {
  await mockBroker(page, (send) => {
    for (let i = 1; i <= 200; i++) send({ seq: i, type: 'LINE', stream: 'STDOUT', line: `line ${i}` })
  })
  await page.goto('/environments/a/containers/c1/logs')
  const log = page.getByRole('log', { name: 'Container log' })
  await expect(log).toContainText('line 200')
  await log.evaluate((el) => (el.scrollTop = 0))
  await page.getByRole('button', { name: 'Jump to latest' }).click()
  await expect(page.getByRole('button', { name: 'Jump to latest' })).toHaveCount(0)
  await expect.poll(() => log.evaluate((el) => el.scrollHeight - el.scrollTop - el.clientHeight)).toBeLessThan(5)
})

test('shows the ended state', async ({ page }) => {
  await mockBroker(page, (send) => {
    send({ seq: 1, type: 'LINE', stream: 'STDOUT', line: 'bye' })
    send({ seq: 2, type: 'STATUS', status: 'ENDED' })
  })
  await page.goto('/environments/a/containers/c1/logs')
  await expect(page.getByRole('status')).toHaveText('Ended')
  await expect(page.getByText('The log stream ended')).toBeVisible()
})

test('shows an error status from the server', async ({ page }) => {
  await mockBroker(page, (send) => send({ seq: 1, type: 'STATUS', status: 'ERROR', line: 'NOT_FOUND' }))
  await page.goto('/environments/a/containers/c1/logs')
  await expect(page.getByRole('alert')).toContainText('no longer exists')
})
