import { expect, test } from './fixtures/api.ts'

// Smoke check, no pixel baselines: each main page renders in light and dark at phone, laptop and wide
// viewports without a page-level horizontal scrollbar. Screenshots are attached for manual review.

const me = { body: { username: 'u', role: 'ADMIN', setupRequired: false } }
const local = {
  id: 'a',
  name: 'Local',
  connectionType: 'LOCAL',
  endpoint: 'unix:///var/run/docker.sock',
  status: 'UP',
  error: null,
  dockerVersion: '27.1.2',
  cpus: 8,
  memoryBytes: 16 * 1024 ** 3,
  containers: { running: 2, stopped: 1, healthy: 1, unhealthy: 0 },
  images: 2,
  volumes: 1,
}
const c = { status: 'Up 2 hours', health: null, created: '2026-10-01T10:00:00Z', ports: [], composeProject: null, composeService: null }
const containers = [
  { ...c, id: '1', name: 'web-with-a-rather-long-container-name', image: 'registry.example.com/team/nginx:1.27', state: 'running', health: 'healthy', ports: ['8080:80/tcp'], composeProject: 'shop' },
  { ...c, id: '2', name: 'api', image: 'app:2', state: 'exited', composeProject: 'shop' },
  { ...c, id: '3', name: 'cache', image: 'redis:7', state: 'running' },
]
const deployments = [
  { name: 'shop', containerCount: 2, runningCount: 1, status: 'PARTIAL', workingDir: '/srv/shop' },
  { name: 'blog', containerCount: 1, runningCount: 1, status: 'RUNNING', workingDir: null },
]
const images = [
  { id: 'sha256:aaaaaaaaaaaaaaaa', repoTags: ['nginx:1'], size: 5 * 1024 ** 2, created: '2026-10-01T10:00:00Z', inUse: true },
  { id: 'sha256:bbbbbbbbbbbbbbbb', repoTags: [], size: 1024, created: '2026-08-01T10:00:00Z', inUse: false },
]
const networks = [{ id: '1', name: 'bridge', driver: 'bridge', scope: 'local', subnet: '172.17.0.0/16', internal: false, containerCount: 2 }]
const volumes = [{ name: 'pgdata', driver: 'local', mountpoint: '/v/pgdata', created: null, inUse: true }]

const pages = [
  { name: 'overview', path: '/' },
  { name: 'dashboard', path: '/environments/a' },
  { name: 'containers', path: '/environments/a/containers' },
  { name: 'deployments', path: '/environments/a/deployments' },
  { name: 'images', path: '/environments/a/images' },
  { name: 'networks', path: '/environments/a/networks' },
  { name: 'volumes', path: '/environments/a/volumes' },
  { name: 'account', path: '/account' },
]
const viewports = [
  { name: '375', width: 375, height: 800 },
  { name: '1280', width: 1280, height: 800 },
  { name: '1920', width: 1920, height: 1080 },
]
const schemes = ['light', 'dark'] as const

test.beforeEach(({ api }) => {
  api.mock('GET', '/api/auth/me', me)
  api.mock('GET', '/api/hosts/overview', { body: [local] })
  api.mock('GET', '/api/hosts/a/containers', { body: containers })
  api.mock('GET', '/api/hosts/a/deployments', { body: deployments })
  api.mock('GET', '/api/hosts/a/managed-deployments', { body: [] })
  api.mock('GET', '/api/hosts/a/images', { body: images })
  api.mock('GET', '/api/hosts/a/networks', { body: networks })
  api.mock('GET', '/api/hosts/a/volumes', { body: volumes })
  api.mock('GET', '/api/notifications/preferences', { body: [] })
})

for (const scheme of schemes) {
  for (const viewport of viewports) {
    for (const target of pages) {
      test(`${target.name} at ${viewport.name}px, ${scheme}: no horizontal scroll`, async ({ page }, testInfo) => {
        await page.emulateMedia({ colorScheme: scheme })
        await page.setViewportSize({ width: viewport.width, height: viewport.height })
        await page.goto(target.path)
        await expect(page.getByRole('main')).toBeVisible()
        await expect(page.getByText('Loading')).toHaveCount(0)
        const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)
        expect(overflow, 'page-level horizontal overflow in px').toBeLessThanOrEqual(0)
        await testInfo.attach(`${target.name}-${viewport.name}-${scheme}`, {
          body: await page.screenshot(),
          contentType: 'image/png',
        })
      })
    }
  }
}
