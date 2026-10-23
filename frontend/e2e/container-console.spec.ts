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
  containers: { running: 1, stopped: 1, healthy: 0, unhealthy: 0 },
  images: 0,
  volumes: 0,
}
const base = { image: 'nginx:1', status: 'Up', health: null, created: '2026-10-01T10:00:00Z', ports: [], composeProject: null, composeService: null }
const containers = [
  { ...base, id: 'c1', name: 'web', state: 'running' },
  { ...base, id: 'c2', name: 'old', state: 'exited' },
]
const session = { sessionId: 's1', output: '/topic/exec/s1', input: '/app/exec/s1/input', resize: '/app/exec/s1/resize' }

const stompFrame = (command: string, headers: Record<string, string>, body = '') =>
  `${command}\n${Object.entries(headers).map(([k, v]) => `${k}:${v}`).join('\n')}\n\n${body}\0`

/** Minimal STOMP broker: pushes output on SUBSCRIBE and records SEND frames (destination + body). */
async function mockBroker(page: Page) {
  const sends: { destination: string; body: string }[] = []
  await page.routeWebSocket('**/ws', (ws) => {
    ws.onMessage((raw) => {
      const text = String(raw)
      if (text.startsWith('CONNECT') || text.startsWith('STOMP')) {
        ws.send(stompFrame('CONNECTED', { version: '1.2', 'heart-beat': '0,0' }))
      } else if (text.startsWith('SUBSCRIBE')) {
        const id = /\nid:([^\n]*)/.exec(text)?.[1] ?? '0'
        const out = (body: object) =>
          ws.send(stompFrame('MESSAGE', { subscription: id, destination: session.output, 'message-id': String(Math.random()) }, JSON.stringify(body)))
        out({ seq: 1, type: 'OUTPUT', data: Buffer.from('prompt$ ').toString('base64') })
      } else if (text.startsWith('SEND')) {
        const [head, ...rest] = text.split('\n\n')
        const destination = /destination:([^\n]*)/.exec(head)?.[1] ?? ''
        sends.push({ destination, body: rest.join('\n\n').replace(/\0$/, '') })
      }
    })
  })
  return sends
}

function asRole(api: ApiMock, role: string) {
  api.mock('GET', '/api/auth/me', { body: { username: 'u', role, setupRequired: false } })
  api.mock('GET', '/api/hosts/overview', { body: [host] })
  api.mock('GET', '/api/hosts/a/containers', { body: containers })
  api.mock('GET', '/api/hosts/a/deployments', { body: [] })
}

test('admin opens a console from a running container, sees output, typing and resize are sent', async ({ page, api }) => {
  asRole(api, 'ADMIN')
  api.mock('POST', '/api/hosts/a/containers/c1/exec', { status: 201, body: session })
  const sends = await mockBroker(page)
  await page.goto('/environments/a/containers')
  await expect(page.getByRole('link', { name: 'Console of old' })).toHaveCount(0)
  await page.getByRole('link', { name: 'Console of web' }).click()
  await expect(page).toHaveURL(/\/environments\/a\/containers\/c1\/console$/)
  await page.getByRole('button', { name: 'Connect' }).click()
  await expect(page.getByRole('status')).toHaveText('Connected')
  await expect(page.locator('.xterm-rows')).toContainText('prompt$')
  await page.keyboard.type('ls')
  await expect.poll(() => sends.filter((s) => s.destination === session.input).map((s) => s.body).join('')).toBe('ls')
  await page.setViewportSize({ width: 700, height: 500 })
  await expect.poll(() => sends.some((s) => s.destination === session.resize && /"cols":\d+/.test(s.body))).toBe(true)
  await page.getByRole('button', { name: 'Disconnect' }).click()
  await expect(page.getByRole('status')).toHaveText('Disconnected')
})

test('shows the server error when the container is not running', async ({ page, api }) => {
  asRole(api, 'ADMIN')
  api.mock('POST', '/api/hosts/a/containers/c1/exec', { status: 409, body: { code: 'INVALID_CONTAINER_STATE', message: 'x' } })
  await mockBroker(page)
  await page.goto('/environments/a/containers/c1/console')
  await page.getByRole('button', { name: 'Connect' }).click()
  await expect(page.getByRole('alert')).toContainText('not running')
  await expect(page.getByRole('status')).toHaveText('Not connected')
})

for (const role of ['OPERATOR', 'VIEWER']) {
  test(`${role} has no console link and is not allowed on the route`, async ({ page, api }) => {
    asRole(api, role)
    await page.goto('/environments/a/containers')
    await expect(page.getByRole('link', { name: 'Logs of web' })).toBeVisible()
    await expect(page.getByRole('link', { name: 'Console of web' })).toHaveCount(0)
    await page.goto('/environments/a/containers/c1/console')
    await expect(page.getByRole('button', { name: 'Connect' })).toHaveCount(0)
    await expect(page.getByRole('heading', { name: 'Not allowed' })).toBeVisible()
  })
}
