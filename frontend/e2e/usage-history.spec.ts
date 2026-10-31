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
const details = {
  id: 'c1',
  name: 'web',
  image: 'nginx:1',
  state: 'exited',
  created: '2026-10-01T10:00:00Z',
  startedAt: null,
  restartCount: 0,
  restartPolicy: { name: '', maxRetries: 0 },
  command: [],
  cmd: [],
  privileged: false,
  hostNetwork: false,
  env: [],
  imageEnv: [],
  mounts: [],
  labels: {},
  networks: [],
  ports: [],
  health: null,
  composeProject: null,
  composeService: null,
}

const MIN = 60_000
const MiB = 1024 * 1024
/** 30 minutes of one-minute buckets ending now; the network counter resets (restart) after 20 minutes. */
function history(containers: string[], extra: Record<string, unknown> = {}) {
  const now = Math.floor(Date.now() / MIN) * MIN
  const points = (seed: number) =>
    Array.from({ length: 30 }, (_, i) => ({
      time: new Date(now - (29 - i) * MIN).toISOString(),
      cpuPercent: 10 + seed + (i % 5),
      memoryBytes: (100 + seed) * MiB,
      networkRxBytes: (i < 20 ? i : i - 20) * 6 * MiB,
      networkTxBytes: i * MiB,
      blockReadBytes: 0,
      blockWriteBytes: i * MiB,
    }))
  return {
    range: '1h',
    from: new Date(now - 60 * MIN).toISOString(),
    to: new Date(now).toISOString(),
    bucketSeconds: 60,
    seriesTotal: containers.length,
    truncated: false,
    series: containers.map((containerId, n) => ({ containerId, points: points(n * 5) })),
    ...extra,
  }
}

test.beforeEach(({ api }) => {
  api.mock('GET', '/api/auth/me', { body: { username: 'u', role: 'VIEWER', setupRequired: false } })
  api.mock('GET', '/api/hosts/overview', { body: [host] })
})

test('container details: charts, range selector and keyboard tooltip', async ({ page, api }) => {
  api.mock('GET', '/api/hosts/a/containers/c1', { body: details })
  api.mock('GET', '/api/hosts/a/stats/history', { body: history(['c1']) })
  await page.goto('/environments/a/containers/c1')

  await expect(page.getByRole('heading', { name: 'Usage history' })).toBeVisible()
  const cpu = page.getByRole('img', { name: /^CPU, last hour: min 10/ })
  await expect(cpu).toBeVisible()
  await expect(page.getByRole('img', { name: /^Memory/ })).toBeVisible()
  await expect(page.getByRole('img', { name: /^Network/ })).toBeVisible()
  await expect(page.getByRole('img', { name: /^Disk/ })).toBeVisible()
  // The restart (counter reset) is a gap, never a negative rate.
  await expect(page.getByRole('img', { name: /^Network/ })).not.toHaveAttribute('aria-label', /-\d/)

  await cpu.focus()
  await page.keyboard.press('End')
  await expect(page.getByRole('status').filter({ hasText: '%' })).toBeVisible()
  await page.keyboard.press('Escape')

  const request = page.waitForRequest((r) => r.url().includes('/stats/history') && r.url().includes('range=7d'))
  await page.getByLabel('Time range').selectOption('7d')
  await request
  await expect(page).toHaveURL(/usageRange=7d/)
})

test('container details: empty state when nothing is recorded yet', async ({ page, api }) => {
  api.mock('GET', '/api/hosts/a/containers/c1', { body: details })
  api.mock('GET', '/api/hosts/a/stats/history', { body: history([]) })
  await page.goto('/environments/a/containers/c1')
  await expect(page.getByText('No history yet: samples are recorded every minute.')).toBeVisible()
})

test('container details: error with retry', async ({ page, api }) => {
  api.mock('GET', '/api/hosts/a/containers/c1', { body: details })
  api.mock('GET', '/api/hosts/a/stats/history', { status: 500, body: { code: 'INTERNAL', message: 'boom' } })
  await page.goto('/environments/a/containers/c1')
  await expect(page.getByText('Could not load usage history.')).toBeVisible()
  api.mock('GET', '/api/hosts/a/stats/history', { body: history(['c1']) })
  await page.getByRole('button', { name: 'Retry' }).click()
  await expect(page.getByRole('img', { name: /^CPU/ })).toBeVisible()
})

test('dashboard: aggregate card says when the list is truncated', async ({ page, api }) => {
  api.mock('GET', '/api/hosts/a/disk-usage', { status: 403, body: { code: 'FORBIDDEN', message: 'no' } })
  api.mock('GET', '/api/hosts/a/stats/history', { body: history(['c1', 'c2'], { truncated: true, seriesTotal: 80 }) })
  await page.goto('/environments/a')
  await expect(page.getByRole('heading', { name: 'Usage history' })).toBeVisible()
  await expect(page.getByRole('img', { name: /^CPU \(all containers\)/ })).toBeVisible()
  await expect(page.getByRole('img', { name: /^Memory \(all containers\)/ })).toBeVisible()
  await expect(page.getByText('Showing the 2 most recently active containers')).toBeVisible()
})

test('dashboard: no card when the history is not visible to the user', async ({ page, api }) => {
  api.mock('GET', '/api/hosts/a/disk-usage', { status: 403, body: { code: 'FORBIDDEN', message: 'no' } })
  api.mock('GET', '/api/hosts/a/stats/history', { status: 404, body: { code: 'HOST_NOT_FOUND', message: 'nope' } })
  await page.goto('/environments/a')
  await expect(page.getByRole('heading', { name: 'Local' })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Usage history' })).toHaveCount(0)
})
