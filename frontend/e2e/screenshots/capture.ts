/**
 * README screenshots. Not a Playwright spec (no `.spec.ts`), so the normal e2e run never picks it up.
 *
 * Usage (from frontend/, Vite dev server NOT required; the script starts nothing):
 *   E2E_PORT=5190 pnpm dev --port 5190 --strictPort    # in another terminal
 *   E2E_PORT=5190 node e2e/screenshots/capture.ts      # writes ../docs/images/*.png
 *
 * No backend and no database: every /api call and the STOMP broker are mocked in the browser.
 * The data mirrors the demo seed (backend/src/main/resources/db/testdata/R__demo_seed.sql):
 * environment "Local", deployment "demo-web", admin "demo-admin".
 */
import { chromium, type Page } from '@playwright/test'
import { mkdirSync } from 'node:fs'
import { join } from 'node:path'

const port = Number(process.env.E2E_PORT ?? 5190)
if (port === 5173) throw new Error('Use a port other than 5173 for the capture (E2E_PORT).')
const base = `http://localhost:${port}`
const outDir = join(import.meta.dirname, '..', '..', '..', 'docs', 'images')

const HOST = '0de00000-0000-4000-8000-000000000101'
const DEPLOYMENT = '0de00000-0000-4000-8000-000000000201'
const CONTAINER = 'c0ffee000001'
const MANAGED = `/api/hosts/${HOST}/managed-deployments`
const now = '2026-10-23T10:00:00Z'

const host = {
  id: HOST,
  name: 'Local',
  connectionType: 'LOCAL',
  endpoint: 'unix:///var/run/docker.sock',
  status: 'UP',
  error: null,
  dockerVersion: '27.1.2',
  cpus: 8,
  memoryBytes: 16 * 1024 ** 3,
  containers: { running: 1, stopped: 0, healthy: 0, unhealthy: 0 },
  images: 1,
  volumes: 0,
}
const admin = { username: 'demo-admin', role: 'ADMIN', setupRequired: false }
const demoWeb = {
  id: DEPLOYMENT,
  name: 'demo-web',
  status: 'STOPPED',
  source: 'FILE',
  lastDeployedAt: null,
  createdAt: '2026-10-01T00:00:00Z',
  yaml: 'services:\n  web:\n    image: nginx:alpine\n    ports:\n      - "8081:80"\n    restart: unless-stopped\n',
  envKeys: [],
}
const container = {
  id: CONTAINER,
  name: 'demo-web',
  image: 'nginx:alpine',
  state: 'running',
  status: 'Up 3 hours',
  health: null,
  created: '2026-10-01T00:00:00Z',
  ports: [],
  composeProject: 'demo-web',
  composeService: 'web',
}

const frame = (command: string, headers: Record<string, string>, body = '') =>
  `${command}\n${Object.entries(headers).map(([k, v]) => `${k}:${v}`).join('\n')}\n\n${body}\0`

type Route = { status?: number; body?: unknown }

/** Answers every /api call from `routes` ("GET /api/..."); anything else answers 501 and is reported. */
async function mockApi(page: Page, routes: Record<string, Route>) {
  const unmocked: string[] = []
  await page.route(
    (url) => url.pathname.startsWith('/api/'),
    async (route) => {
      const request = route.request()
      const key = `${request.method()} ${new URL(request.url()).pathname}`
      const hit = routes[key]
      if (!hit) {
        unmocked.push(key)
        await route.fulfill({ status: 501, json: { error: `No capture mock for ${key}` } })
        return
      }
      await route.fulfill({ status: hit.status ?? 200, json: hit.body ?? null })
    },
  )
  return unmocked
}

/** Minimal STOMP broker. `onSubscribe` gets the destination and a sender for that subscription. */
async function mockBroker(page: Page, onSubscribe: (destination: string, send: (body: object) => void) => void) {
  await page.routeWebSocket('**/ws', (ws) => {
    ws.onMessage((raw) => {
      const text = String(raw)
      if (text.startsWith('CONNECT') || text.startsWith('STOMP')) {
        ws.send(frame('CONNECTED', { version: '1.2', 'heart-beat': '0,0' }))
      } else if (text.startsWith('SUBSCRIBE')) {
        const id = /id:(.*)/.exec(text)?.[1] ?? '0'
        const destination = /destination:(.*)/.exec(text)?.[1] ?? ''
        const send = (body: object) =>
          ws.send(frame('MESSAGE', { subscription: id, destination, 'message-id': String(Math.random()) }, JSON.stringify(body)))
        onSubscribe(destination, send)
      }
    })
  })
}

const session = (id: string, title: string) => ({ id, title, sharedWithAdmin: false, createdAt: now, updatedAt: now, ownerUsername: null })
const message = (id: string, seq: number, role: string, content: string | null, extra: object = {}) => ({
  id, seq, role, content, toolCalls: null, toolCallId: null, status: null, createdAt: now, ...extra,
})

async function shoot(page: Page, name: string) {
  await page.screenshot({ path: join(outDir, `${name}.png`) })
  console.log(`wrote docs/images/${name}.png`)
}

async function main() {
  mkdirSync(outDir, { recursive: true })
  const browser = await chromium.launch()
  const context = await browser.newContext({ viewport: { width: 1280, height: 800 }, reducedMotion: 'reduce', colorScheme: 'light' })
  const page = await context.newPage()
  const problems: string[] = []

  // 1. Environments overview
  {
    const unmocked = await mockApi(page, {
      'GET /api/auth/me': { body: admin },
      'GET /api/notifications/unread-count': { body: { count: 1 } },
      'GET /api/hosts/overview': { body: [host] },
    })
    await page.goto(`${base}/`)
    await page.getByRole('link', { name: /Local/ }).waitFor()
    await shoot(page, 'environments-overview')
    problems.push(...unmocked)
  }

  // 2. Compose deployment wizard (the Add deployment dialog in wizard mode)
  {
    const unmocked = await mockApi(page, {
      'GET /api/auth/me': { body: admin },
      'GET /api/notifications/unread-count': { body: { count: 1 } },
      'GET /api/hosts/overview': { body: [host] },
      [`GET /api/hosts/${HOST}/deployments`]: { body: [] },
      [`GET ${MANAGED}`]: { body: [demoWeb] },
      [`GET ${MANAGED}/${DEPLOYMENT}`]: { body: demoWeb },
      [`GET ${MANAGED}/${DEPLOYMENT}/versions`]: { body: [] },
      [`GET ${MANAGED}/${DEPLOYMENT}/files`]: { body: [] },
    })
    await page.goto(`${base}/environments/${HOST}/deployments`)
    await page.getByRole('button', { name: 'Add deployment' }).click()
    await page.getByLabel('Name', { exact: true }).fill('shop')
    await page.getByRole('radio', { name: 'Form wizard' }).check()
    await page.getByLabel('Service 1 name').fill('web')
    await page.getByLabel('Service 1 image').fill('nginx:1.27')
    await page.getByRole('button', { name: 'Add service' }).click()
    await page.getByLabel('Service 2 name').fill('db')
    await page.getByLabel('Service 2 image').fill('postgres:16')
    await page.getByRole('button', { name: 'Add network' }).click()
    await page.getByLabel('Network 1 name').fill('front')
    await page.getByRole('group', { name: 'Service 1' }).getByRole('checkbox', { name: 'front' }).check()
    await page.getByRole('button', { name: 'Add env var to service 2' }).click()
    await page.getByLabel('Service 2 env 1 name').fill('POSTGRES_PASSWORD')
    await page.getByRole('group', { name: 'Service 2' }).getByLabel('From deployment variable').check()
    await page.getByLabel('Service 2 env 1 reference').fill('DB_PASSWORD')
    await page.getByLabel('Name', { exact: true }).scrollIntoViewIfNeeded()
    await shoot(page, 'deployment-wizard')
    problems.push(...unmocked)
  }

  // 3. Container logs (STOMP mocked; lines mirror an nginx container)
  {
    const unmocked = await mockApi(page, {
      'GET /api/auth/me': { body: admin },
      'GET /api/notifications/unread-count': { body: { count: 1 } },
      'GET /api/hosts/overview': { body: [host] },
      [`GET /api/hosts/${HOST}/containers`]: { body: [container] },
      [`GET /api/hosts/${HOST}/images`]: { body: [] },
      [`GET /api/hosts/${HOST}/deployments`]: { body: [] },
    })
    await mockBroker(page, (destination, send) => {
      if (!destination.endsWith('/logs')) return
      const lines = [
        'docker-entrypoint.sh: Configuration complete; ready for start up',
        'start worker process 29',
        '10.0.0.5 - - "GET / HTTP/1.1" 200 615 "-" "Mozilla/5.0"',
        '10.0.0.5 - - "GET /static/app.css HTTP/1.1" 200 1820 "-" "Mozilla/5.0"',
        '10.0.0.7 - - "GET /health HTTP/1.1" 200 2 "-" "curl/8.5.0"',
      ]
      lines.forEach((line, i) => send({ seq: i + 1, type: 'LINE', stream: 'STDOUT', line }))
      send({ seq: lines.length + 1, type: 'LINE', stream: 'STDERR', line: '2026/10/23 10:02:14 [warn] 29#29: upstream is slow' })
    })
    await page.goto(`${base}/environments/${HOST}/containers/${CONTAINER}/logs`)
    await page.getByRole('log', { name: 'Container log' }).getByText('upstream is slow').waitFor()
    await shoot(page, 'container-logs')
    problems.push(...unmocked)
  }

  // 4. AI assistant: a chat where the assistant proposes a deploy and waits for Confirm
  {
    const pendingDeploy = message('m3', 3, 'TOOL', 'Waiting for confirmation', {
      status: 'PENDING',
      toolCallId: 'c1',
      toolCalls: [{ id: 'c1', name: 'run_deployment', arguments: { deploymentId: DEPLOYMENT, env: '<hidden>' }, token: 't', description: 'Deploy "demo-web" on Local' }],
    })
    const messages = [
      message('m1', 1, 'USER', 'What is running on Local?'),
      message('m2', 2, 'ASSISTANT', 'One container is running: demo-web (nginx:alpine). The deployment itself is stopped.'),
      message('m4', 4, 'USER', 'Deploy demo-web for me.'),
      message('m5', 5, 'ASSISTANT', 'I can deploy it. This starts the containers, so please confirm first.'),
      pendingDeploy,
    ]
    const unmocked = await mockApi(page, {
      'GET /api/auth/me': { body: admin },
      'GET /api/notifications/unread-count': { body: { count: 1 } },
      'GET /api/ai/sessions': { body: { items: [session('s1', 'Deploy demo-web'), session('s0', 'Running containers')], page: 0, size: 100, totalElements: 2 } },
      'GET /api/ai/sessions/s1': { body: { session: session('s1', 'Deploy demo-web'), messages } },
    })
    await mockBroker(page, () => {})
    await page.goto(`${base}/assistant/s1`)
    await page.getByRole('button', { name: 'Confirm' }).waitFor()
    await shoot(page, 'ai-chat')
    problems.push(...unmocked)
  }

  await browser.close()
  if (problems.length > 0) throw new Error(`Unmocked API calls: ${[...new Set(problems)].join(', ')}`)
}

main().catch((error) => {
  console.error(error)
  process.exitCode = 1
})
