import { expect, test } from './fixtures/api.ts'
import { mockBroker } from './fixtures/stomp.ts'

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

test.beforeEach(({ api }) => {
  api.mock('GET', '/api/auth/me', { body: { username: 'u', role: 'VIEWER', setupRequired: false } })
  api.mock('GET', '/api/hosts/overview', { body: [host] })
  api.mock('GET', '/api/hosts/a/containers', { body: [container] })
  api.mock('GET', '/api/hosts/a/images', { body: [] })
  api.mock('GET', '/api/hosts/a/deployments', { body: [] })
})

test('opens the logs from the container row and streams lines, stderr marked', async ({ page }) => {
  const subs = await mockBroker(page, (send) => {
    send({ seq: 1, type: 'LINE', stream: 'STDOUT', line: 'listening on 80' })
    send({ seq: 2, type: 'LINE', stream: 'STDERR', line: 'warn: slow' })
  })
  await page.goto('/environments/a/containers')
  await page.getByRole('button', { name: 'Logs web' }).click()
  await expect(page).toHaveURL(/\/environments\/a\/containers\/c1\/logs$/)
  const log = page.getByRole('log', { name: 'Container log' })
  await expect(log).toContainText('listening on 80')
  await expect(log).toContainText('warn: slow')
  await expect(log.locator('.logv__line--err')).toHaveText('warn: slow')
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

test('searches with highlighting, steps through matches and filters stderr only', async ({ page }) => {
  await mockBroker(page, (send) => {
    send({ seq: 1, type: 'LINE', stream: 'STDOUT', line: 'Error one' })
    send({ seq: 2, type: 'LINE', stream: 'STDOUT', line: 'all fine' })
    send({ seq: 3, type: 'LINE', stream: 'STDERR', line: 'an error and another ERROR' })
  })
  await page.goto('/environments/a/containers/c1/logs')
  const log = page.getByRole('log', { name: 'Container log' })
  await expect(log).toContainText('all fine')
  const box = page.getByRole('searchbox', { name: 'Search logs' })
  await box.fill('error')
  await expect(log.locator('mark')).toHaveCount(3)
  await expect(page.getByText('1 of 3')).toBeVisible()
  await box.press('Enter')
  await expect(page.getByText('2 of 3')).toBeVisible()
  await box.press('Shift+Enter')
  await expect(page.getByText('1 of 3')).toBeVisible()
  await page.getByLabel('stderr only').check()
  await expect(log).not.toContainText('all fine')
  await expect(log.locator('mark')).toHaveCount(2)
  await expect(page.getByText('Showing 1 of 3 lines')).toBeVisible()
  await page.getByRole('button', { name: 'Clear filters' }).click()
  await expect(log).toContainText('all fine')
})

test('downloads the log with the selected tail and timestamps', async ({ page, api }) => {
  api.mock('GET', '/api/hosts/a/containers/c1/logs/download', { body: null })
  await mockBroker(page, () => {})
  await page.goto('/environments/a/containers/c1/logs')
  await page.getByLabel('Show last').selectOption('1000')
  await page.getByLabel('Timestamps').check()
  await expect(page.getByText(/Downloads are capped/)).toBeVisible()
  const [download] = await Promise.all([page.waitForEvent('download'), page.getByRole('link', { name: /Download/ }).click()])
  expect(download.url()).toContain('/api/hosts/a/containers/c1/logs/download?tail=1000&timestamps=true')
})

test('explains a refused log stream', async ({ page }) => {
  await mockBroker(page, () => {}, { refuse: true })
  await page.goto('/environments/a/containers/c1/logs')
  await expect(page.getByRole('alert')).toContainText('not allowed')
})
