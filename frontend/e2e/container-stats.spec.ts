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
  containers: { running: 1, stopped: 1, healthy: 0, unhealthy: 0 },
  images: 0,
  volumes: 0,
}
const container = (id: string, name: string, state: string) => ({
  id,
  name,
  image: 'nginx:1',
  state,
  status: state === 'running' ? 'Up' : 'Exited',
  health: null,
  created: '2026-10-01T10:00:00Z',
  ports: [],
  composeProject: null,
  composeService: null,
})

const stompFrame = (command: string, headers: Record<string, string>, body = '') =>
  `${command}\n${Object.entries(headers).map(([k, v]) => `${k}:${v}`).join('\n')}\n\n${body}\0`

/** Minimal STOMP broker; returns a function that pushes a frame to the stats topic. */
async function mockBroker(page: Page) {
  let push: (body: object) => void = () => {}
  await page.routeWebSocket('**/ws', (ws) => {
    ws.onMessage((raw) => {
      const text = String(raw)
      if (text.startsWith('CONNECT') || text.startsWith('STOMP')) {
        ws.send(stompFrame('CONNECTED', { version: '1.2', 'heart-beat': '0,0' }))
      } else if (text.startsWith('SUBSCRIBE') && text.includes('/stats')) {
        const id = /^id:(.*)$/m.exec(text)?.[1] ?? '0'
        push = (body) => ws.send(stompFrame('MESSAGE', { subscription: id, destination: '/topic/hosts/a/stats', 'message-id': String(Math.random()) }, JSON.stringify(body)))
      }
    })
  })
  return (body: object) => push(body)
}

const MiB = 1024 * 1024
const frame = (seq: number, cpu: number) => ({
  seq,
  samples: [{ containerId: 'c1', cpu, memUsed: 80 * MiB, memLimit: 512 * MiB, netRx: seq * MiB, netTx: 0, blkRead: 0, blkWrite: 0 }],
})

test('containers list shows live CPU and memory with sparklines, dashes for stopped', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', { body: { username: 'u', role: 'VIEWER', setupRequired: false } })
  api.mock('GET', '/api/hosts/overview', { body: [host] })
  api.mock('GET', '/api/hosts/a/deployments', { body: [] })
  api.mock('GET', '/api/hosts/a/containers', { body: [container('c1', 'web', 'running'), container('c2', 'old', 'exited')] })
  const push = await mockBroker(page)
  await page.goto('/environments/a/containers')
  const web = page.getByRole('row', { name: /web/ })
  await expect(web).toBeVisible()

  let seq = 0
  await expect.poll(async () => {
    push(frame(++seq, 12.5))
    return web.getByText('12.5%').count()
  }).toBeGreaterThan(0)
  await expect(web.getByText('80 MiB')).toBeVisible()
  await expect(web.getByRole('img', { name: 'CPU history of web' })).toBeVisible()
  await expect(page.getByRole('row', { name: /old/ }).getByRole('img')).toHaveCount(0)
})

test('details page shows resource usage of a running container', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', { body: { username: 'u', role: 'VIEWER', setupRequired: false } })
  api.mock('GET', '/api/hosts/overview', { body: [host] })
  api.mock('GET', '/api/hosts/a/containers/c1', {
    body: {
      ...container('c1', 'web', 'running'),
      startedAt: '2026-10-01T10:00:05Z',
      restartCount: 0,
      restartPolicy: { name: '', maxRetries: 0 },
      command: [],
      env: [],
      mounts: [],
      labels: {},
      networks: [],
      ports: [],
      health: null,
    },
  })
  const push = await mockBroker(page)
  await page.goto('/environments/a/containers/c1')
  await expect(page.getByRole('heading', { name: 'Resource usage' })).toBeVisible()

  let seq = 0
  await expect.poll(async () => {
    push(frame(++seq, 42))
    return page.getByText('42.0%').count()
  }).toBeGreaterThan(0)
  await expect(page.getByText('80 MiB / 512 MiB')).toBeVisible()
  await expect(page.getByRole('img', { name: 'CPU history' })).toBeVisible()
})
