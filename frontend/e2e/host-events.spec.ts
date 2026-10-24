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
const container = (name: string) => ({
  id: name,
  name,
  image: 'nginx:1',
  state: 'running',
  status: 'Up',
  health: null,
  created: '2026-10-01T10:00:00Z',
  ports: [],
  composeProject: null,
  composeService: null,
})

const stompFrame = (command: string, headers: Record<string, string>, body = '') =>
  `${command}\n${Object.entries(headers).map(([k, v]) => `${k}:${v}`).join('\n')}\n\n${body}\0`

/** Minimal STOMP broker; returns a function that pushes a frame to the host events topic. */
async function mockBroker(page: Page) {
  let push: (body: object) => void = () => {}
  await page.routeWebSocket('**/ws', (ws) => {
    ws.onMessage((raw) => {
      const text = String(raw)
      if (text.startsWith('CONNECT') || text.startsWith('STOMP')) {
        ws.send(stompFrame('CONNECTED', { version: '1.2', 'heart-beat': '0,0' }))
      } else if (text.startsWith('SUBSCRIBE') && text.includes('/events')) {
        const id = /^id:(.*)$/m.exec(text)?.[1] ?? '0'
        push = (body) => ws.send(stompFrame('MESSAGE', { subscription: id, destination: '/topic/hosts/a/events', 'message-id': String(Math.random()) }, JSON.stringify(body)))
      }
    })
  })
  return (body: object) => push(body)
}

test('a container event refreshes the open containers list without reload', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', { body: { username: 'u', role: 'VIEWER', setupRequired: false } })
  api.mock('GET', '/api/hosts/overview', { body: [host] })
  api.mock('GET', '/api/hosts/a/deployments', { body: [] })
  api.mock('GET', '/api/hosts/a/containers', { body: [container('web')] })
  api.mock('GET', '/api/hosts/a/images', { body: [] })
  const push = await mockBroker(page)
  await page.goto('/environments/a/containers')
  await expect(page.getByText('web', { exact: true })).toBeVisible()

  api.mock('GET', '/api/hosts/a/containers', { body: [container('web'), container('worker')] })
  api.mock('GET', '/api/hosts/a/images', { body: [] })
  await expect.poll(async () => {
    push({ seq: 1, type: 'EVENT', events: [{ type: 'CONTAINER', action: 'start', id: 'worker', time: '2026-10-24T10:00:00Z' }] })
    return page.getByText('worker', { exact: true }).count()
  }).toBeGreaterThan(0)
})
