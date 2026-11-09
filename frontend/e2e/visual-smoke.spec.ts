import { expect, mockDeploymentCards, test } from './fixtures/api.ts'

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

const DISK_USAGE = {
  images: { count: 12, totalSize: 1024 ** 3, reclaimableSize: 0 },
  containers: { count: 3, totalSize: 0, reclaimableSize: 0 },
  volumes: { count: 4, totalSize: 0, reclaimableSize: 0 },
  buildCache: { count: 0, totalSize: 0, reclaimableSize: 0 },
}
const pages: { name: string; path: string; ready?: string }[] = [
  { name: 'overview', path: '/' },
  // `ready`: accessible name of the page's h1 once its data has rendered (the dashboard shows a skeleton until then).
  { name: 'dashboard', path: '/environments/a', ready: 'Local' },
  { name: 'containers', path: '/environments/a/containers' },
  { name: 'deployments', path: '/environments/a/deployments' },
  { name: 'images', path: '/environments/a/images' },
  { name: 'networks', path: '/environments/a/networks' },
  { name: 'volumes', path: '/environments/a/volumes' },
  { name: 'account', path: '/account' },
  { name: 'notifications', path: '/notifications' },
  { name: 'deployment-detail', path: '/environments/a/deployments/s1' },
  { name: 'assistant', path: '/assistant/s1' },
  { name: 'ai-settings', path: '/settings/ai' },
  { name: 'retention-settings', path: '/settings/retention' },
  { name: 'webhook-settings', path: '/settings/notifications' },
]
const aiSettings = {
  activeProvider: 'OPENAI_COMPATIBLE',
  enabled: true,
  providers: [
    { provider: 'OPENAI_COMPATIBLE', baseUrl: 'https://integrate.api.nvidia.example/v1', model: 'glm-5.3-flash-long-model-name', keyConfigured: true, apiKeyHint: '…9f2c' },
    { provider: 'GEMINI', baseUrl: 'https://generativelanguage.googleapis.com', model: 'gemini-x', keyConfigured: false, apiKeyHint: null },
  ],
}
const retention = { auditLogDays: 90, notificationDays: 30, chatSessionDays: 60, statsDays: 7 }
const webhook = {
  id: 'w1',
  name: 'Ops chat with a rather long webhook name',
  urlHint: 'https://hooks.example.com/…x9f2',
  eventTypes: ['HOST_DOWN', 'DEPLOYMENT_FAILED'],
  enabled: true,
  lastDeliveryAt: null,
  lastDeliveryStatus: null,
}
const notifications = [
  { id: 'a', type: 'HOST_DOWN', hostId: 'a', hostName: 'Local', deploymentId: null, deploymentName: null, containerId: null, containerName: null, message: 'Local is unreachable (a), and the message wraps over several lines on a phone', read: false, createdAt: '2026-10-30T10:00:00Z' },
  { id: 'b', type: 'HOST_DOWN', hostId: 'a', hostName: 'Local', deploymentId: null, deploymentName: null, containerId: null, containerName: null, message: 'Local is unreachable (b)', read: true, createdAt: '2026-10-29T10:00:00Z' },
]
const chatSession = { id: 's1', title: 'Deploy help for the shop stack with a long title', sharedWithAdmin: false, createdAt: '2026-10-30T10:00:00Z', updatedAt: '2026-10-30T10:00:00Z', ownerUsername: null }
const chatMessages = [
  { id: 'm1', seq: 1, role: 'USER', content: 'How many deployments run on this host and which of them are unhealthy right now?', toolCalls: null, toolCallId: null, status: null, createdAt: '2026-10-30T10:00:00Z' },
  { id: 'm2', seq: 2, role: 'ASSISTANT', content: 'Two deployments run: shop (1 of 2 containers) and blog (1 of 1).', toolCalls: null, toolCallId: null, status: null, createdAt: '2026-10-30T10:00:01Z' },
]
const deploymentDetail = {
  id: 's1',
  name: 'shop',
  status: 'STOPPED',
  source: 'FILE',
  lastDeployedAt: null,
  createdAt: '2026-10-01T10:00:00Z',
  yaml: 'services:\n  web:\n    image: nginx',
  git: null,
  imageAutoUpdate: null,
  envKeys: ['TOKEN'],
  profiles: [],
}
const deploymentVersions = [
  { id: 'v2', source: 'MANUAL', commitSha: null, createdBy: 'ann', createdAt: '2026-10-02T10:00:00Z', current: true, deployed: true },
  { id: 'v1', source: 'MANUAL', commitSha: null, createdBy: 'ann', createdAt: '2026-10-01T10:00:00Z', current: false, deployed: true },
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
  api.mock('GET', '/api/hosts/a/disk-usage', { body: DISK_USAGE })
  api.mock('GET', '/api/hosts/a/containers', { body: containers })
  api.mock('GET', '/api/hosts/a/deployments', { body: deployments })
  api.mock('GET', '/api/hosts/a/managed-deployments', { body: [] })
  api.mock('GET', '/api/hosts/a/images', { body: images })
  api.mock('GET', '/api/hosts/a/networks', { body: networks })
  api.mock('GET', '/api/hosts/a/volumes', { body: volumes })
  api.mock('GET', '/api/hosts/a/volume-backup-schedules', { body: [] })
  api.mock('GET', '/api/account/tokens', { body: [] })
  api.mock('GET', '/api/notifications/preferences', { body: [] })
  api.mock('GET', '/api/notifications', { body: { content: notifications, totalElements: notifications.length } })
  api.mock('GET', '/api/notifications/unread-count', { body: { count: 1 } })

  const deploymentBase = '/api/hosts/a/managed-deployments/s1'
  api.mock('GET', deploymentBase, { body: deploymentDetail })
  mockDeploymentCards(api, deploymentBase, deploymentVersions)
  for (const v of deploymentVersions) {
    api.mock('GET', `${deploymentBase}/versions/${v.id}`, { body: { ...v, yaml: deploymentDetail.yaml, baselineVersionId: 'v2', env: [], removedKeys: [] } })
  }

  api.mock('GET', '/api/ai/sessions', { body: { items: [chatSession] } })
  api.mock('GET', '/api/ai/sessions/s1', { body: { session: chatSession, messages: chatMessages } })
  api.mock('GET', '/api/settings/ai', { body: aiSettings })
  api.mock('GET', '/api/settings/retention', { body: retention })
  api.mock('GET', '/api/notifications/webhooks', { body: [webhook] })
})

for (const scheme of schemes) {
  for (const viewport of viewports) {
    for (const target of pages) {
      test(`${target.name} at ${viewport.name}px, ${scheme}: no horizontal scroll`, async ({ page }, testInfo) => {
        await page.emulateMedia({ colorScheme: scheme })
        await page.setViewportSize({ width: viewport.width, height: viewport.height })
        await page.goto(target.path)
        // A client-side redirect or a re-render after `load` would destroy the execution context of the check below:
        // wait for the final URL, then for the page's data to be on screen before measuring.
        await page.waitForURL((url) => url.pathname === target.path)
        if (target.ready) await expect(page.getByRole('heading', { name: target.ready, exact: true })).toBeVisible()
        // The assistant page nests its own <main> inside the shell's, so take the outer (shell) one.
        await expect(page.getByRole('main').first()).toBeVisible()
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
