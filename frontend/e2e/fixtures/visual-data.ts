import { mockDeploymentCards, type ApiMock } from './api.ts'

// Mock data shared by visual-smoke.spec.ts (no horizontal scroll) and visual.spec.ts (pixel baselines), so both look at
// the same pages with the same content. Dates are fixed ISO strings; visual.spec.ts also freezes the clock.

export const me = { body: { username: 'u', role: 'ADMIN', setupRequired: false } }
export const local = {
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
export const containers = [
  { ...c, id: '1', name: 'web-with-a-rather-long-container-name', image: 'registry.example.com/team/nginx:1.27', state: 'running', health: 'healthy', ports: ['8080:80/tcp'], composeProject: 'shop' },
  { ...c, id: '2', name: 'api', image: 'app:2', state: 'exited', composeProject: 'shop' },
  { ...c, id: '3', name: 'cache', image: 'redis:7', state: 'running' },
]
export const deployments = [
  { name: 'shop', containerCount: 2, runningCount: 1, status: 'PARTIAL', workingDir: '/srv/shop' },
  { name: 'blog', containerCount: 1, runningCount: 1, status: 'RUNNING', workingDir: null },
]
export const images = [
  { id: 'sha256:aaaaaaaaaaaaaaaa', repoTags: ['nginx:1'], size: 5 * 1024 ** 2, created: '2026-10-01T10:00:00Z', inUse: true },
  { id: 'sha256:bbbbbbbbbbbbbbbb', repoTags: [], size: 1024, created: '2026-08-01T10:00:00Z', inUse: false },
]
export const networks = [{ id: '1', name: 'bridge', driver: 'bridge', scope: 'local', subnet: '172.17.0.0/16', internal: false, containerCount: 2 }]
export const volumes = [{ name: 'pgdata', driver: 'local', mountpoint: '/v/pgdata', created: null, inUse: true }]

export const DISK_USAGE = {
  images: { count: 12, totalSize: 1024 ** 3, reclaimableSize: 0 },
  containers: { count: 3, totalSize: 0, reclaimableSize: 0 },
  volumes: { count: 4, totalSize: 0, reclaimableSize: 0 },
  buildCache: { count: 0, totalSize: 0, reclaimableSize: 0 },
}
export const aiSettings = {
  activeProvider: 'OPENAI_COMPATIBLE',
  enabled: true,
  providers: [
    { provider: 'OPENAI_COMPATIBLE', baseUrl: 'https://integrate.api.nvidia.example/v1', model: 'glm-5.3-flash-long-model-name', keyConfigured: true, apiKeyHint: '…9f2c' },
    { provider: 'GEMINI', baseUrl: 'https://generativelanguage.googleapis.com', model: 'gemini-x', keyConfigured: false, apiKeyHint: null },
  ],
}
export const retention = { auditLogDays: 90, notificationDays: 30, chatSessionDays: 60, statsDays: 7 }
export const webhook = {
  id: 'w1',
  name: 'Ops chat with a rather long webhook name',
  urlHint: 'https://hooks.example.com/…x9f2',
  eventTypes: ['HOST_DOWN', 'DEPLOYMENT_FAILED'],
  enabled: true,
  lastDeliveryAt: null,
  lastDeliveryStatus: null,
}
export const notifications = [
  { id: 'a', type: 'HOST_DOWN', hostId: 'a', hostName: 'Local', deploymentId: null, deploymentName: null, containerId: null, containerName: null, message: 'Local is unreachable (a), and the message wraps over several lines on a phone', read: false, createdAt: '2026-10-30T10:00:00Z' },
  { id: 'b', type: 'HOST_DOWN', hostId: 'a', hostName: 'Local', deploymentId: null, deploymentName: null, containerId: null, containerName: null, message: 'Local is unreachable (b)', read: true, createdAt: '2026-10-29T10:00:00Z' },
]
export const chatSession = { id: 's1', title: 'Deploy help for the shop stack with a long title', sharedWithAdmin: false, createdAt: '2026-10-30T10:00:00Z', updatedAt: '2026-10-30T10:00:00Z', ownerUsername: null }
export const chatMessages = [
  { id: 'm1', seq: 1, role: 'USER', content: 'How many deployments run on this host and which of them are unhealthy right now?', toolCalls: null, toolCallId: null, status: null, createdAt: '2026-10-30T10:00:00Z' },
  { id: 'm2', seq: 2, role: 'ASSISTANT', content: 'Two deployments run: shop (1 of 2 containers) and blog (1 of 1).', toolCalls: null, toolCallId: null, status: null, createdAt: '2026-10-30T10:00:01Z' },
]
export const deploymentDetail = {
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
export const deploymentVersions = [
  { id: 'v2', source: 'MANUAL', commitSha: null, createdBy: 'ann', createdAt: '2026-10-02T10:00:00Z', current: true, deployed: true },
  { id: 'v1', source: 'MANUAL', commitSha: null, createdBy: 'ann', createdAt: '2026-10-01T10:00:00Z', current: false, deployed: true },
]

const userBase = { passwordChangeRequired: false, createdAt: '2026-10-01T10:00:00Z', passwordChangedAt: '2026-10-03T10:00:00Z', disabledAt: null }
export const users = [
  { ...userBase, id: 'u1', username: 'u', role: 'ADMIN', disabled: false },
  { ...userBase, id: 'u2', username: 'anna', role: 'OPERATOR', disabled: false },
  { ...userBase, id: 'u3', username: 'a-very-long-username-for-the-table', role: 'VIEWER', disabled: true, disabledAt: '2026-11-01T10:00:00Z' },
]
export const containerDetails = {
  id: '1',
  name: 'web-with-a-rather-long-container-name',
  image: 'registry.example.com/team/nginx:1.27',
  state: 'running',
  created: '2026-10-01T10:00:00Z',
  startedAt: '2026-10-01T10:00:05Z',
  restartCount: 0,
  restartPolicy: { name: 'always', maxRetries: 0 },
  command: ['nginx', '-g', 'daemon off;'],
  env: [{ key: 'DB_PASSWORD', value: 's3cret' }],
  mounts: [{ type: 'volume', name: 'pgdata', source: '/var/lib/docker/volumes/pgdata', destination: '/data', readOnly: false }],
  labels: { app: 'shop' },
  networks: [{ name: 'bridge', ipAddress: '172.17.0.2', gateway: '172.17.0.1', macAddress: '02:42:ac:11:00:02', aliases: [] }],
  ports: [{ containerPort: 80, protocol: 'tcp', hostIp: '0.0.0.0', hostPort: 8080 }],
  health: { status: 'healthy', failingStreak: 0, recentLogs: [] },
  composeProject: 'shop',
  composeService: 'web',
}

/** Everything the main pages need: shell, lists, the deployment detail with its cards, chat, settings, notifications. */
export function mockCommon(api: ApiMock) {
  api.mock('GET', '/api/auth/me', me)
  api.mock('GET', '/api/hosts/overview', { body: [local] })
  api.mock('GET', '/api/hosts/a/disk-usage', { body: DISK_USAGE })
  api.mock('GET', '/api/hosts/a/containers', { body: containers })
  api.mock('GET', '/api/hosts/a/deployments', { body: deployments })
  api.mock('GET', '/api/hosts/a/managed-deployments', { body: [] })
  api.mock('GET', '/api/hosts/a/proxy/routes', { body: [] })
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
}

/** The users settings page (visual.spec.ts only). */
export function mockUsers(api: ApiMock) {
  api.mock('GET', '/api/users', { body: users })
  api.mock('GET', '/api/settings/security', { body: { require2faForAdmins: false, updatedAt: null } })
}
export function mockContainerDetails(api: ApiMock) {
  api.mock('GET', '/api/hosts/a/containers/1', { body: containerDetails })
}
/** The signed-out state: the app shows the login page. */
export function mockSignedOut(api: ApiMock) {
  api.mock('GET', '/api/auth/me', { status: 401 })
}
