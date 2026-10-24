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
import { chromium, type Browser, type Page } from '@playwright/test'
import { execFileSync } from 'node:child_process'
import { mkdirSync, mkdtempSync, statSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const port = Number(process.env.E2E_PORT ?? 5190)
if (port === 5173) throw new Error('Use a port other than 5173 for the capture (E2E_PORT).')
const base = `http://localhost:${port}`
const outDir = join(import.meta.dirname, '..', '..', '..', 'docs', 'images')

const HOST = '0de00000-0000-4000-8000-000000000101'
const DEPLOYMENT = '0de00000-0000-4000-8000-000000000201'
const CONTAINER = 'c0ffee000001'
const NEW_DEPLOYMENT = '0de00000-0000-4000-8000-000000000202'
const RUN = 'run-demo-shop-1'
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

/** Waits until `check` holds or fails after `ms`. */
async function until(check: () => boolean, ms = 10_000) {
  const start = Date.now()
  while (!check()) {
    if (Date.now() - start > ms) throw new Error('Timed out waiting for the deploy stream subscription')
    await new Promise((resolve) => setTimeout(resolve, 50))
  }
}

/**
 * Deploy flow for the README GIF: Add deployment -> Create -> Deploy -> live output -> Succeeded.
 * Recorded as a WebM video by Playwright, then converted to GIF with ffmpeg (palette, 10 fps, 960 px wide).
 * Skipped with a message when ffmpeg is not on the PATH.
 */
async function captureDeployFlow(browser: Browser, problems: string[]) {
  const workDir = mkdtempSync(join(tmpdir(), 'shipir-deploy-flow-'))
  const context = await browser.newContext({
    viewport: { width: 1280, height: 800 },
    reducedMotion: 'reduce',
    colorScheme: 'light',
    recordVideo: { dir: workDir, size: { width: 1280, height: 800 } },
  })
  const page = await context.newPage()
  const shop = {
    id: NEW_DEPLOYMENT,
    name: 'shop',
    status: 'STOPPED',
    source: 'FILE',
    lastDeployedAt: null,
    createdAt: now,
    yaml: 'services:\n  web:\n    image: nginx:alpine\n    ports:\n      - "8082:80"\n',
    envKeys: [],
  }
  const routes: Record<string, Route> = {
    'GET /api/auth/me': { body: admin },
    'GET /api/notifications/unread-count': { body: { count: 1 } },
    'GET /api/hosts/overview': { body: [host] },
    [`GET /api/hosts/${HOST}/deployments`]: { body: [] },
    [`GET ${MANAGED}`]: { body: [demoWeb] },
    [`POST ${MANAGED}`]: { status: 201, body: shop },
    [`GET ${MANAGED}/${NEW_DEPLOYMENT}`]: { body: shop },
    [`GET ${MANAGED}/${NEW_DEPLOYMENT}/versions`]: { body: [] },
    [`GET ${MANAGED}/${NEW_DEPLOYMENT}/files`]: { body: [] },
    [`POST ${MANAGED}/${NEW_DEPLOYMENT}/deploy`]: { status: 202, body: { runId: RUN, versionId: 'v-shop-1' } },
  }
  const unmocked = await mockApi(page, routes)

  let sender: ((body: object) => void) | null = null
  await mockBroker(page, (destination, send) => {
    if (destination === `/topic/deployments/${RUN}`) sender = send
  })
  const pause = (ms: number) => page.waitForTimeout(ms)

  await page.goto(`${base}/environments/${HOST}/deployments`)
  await page.getByRole('button', { name: 'Add deployment' }).click()
  await page.getByLabel('Name', { exact: true }).fill('shop')
  await page.getByLabel('Compose file').first().focus()
  await page.keyboard.insertText(shop.yaml)
  await pause(800)
  await page.getByRole('button', { name: 'Create deployment' }).click()
  await page.waitForURL(new RegExp(`/deployments/${NEW_DEPLOYMENT}$`))
  await pause(800)

  await page.getByRole('button', { name: 'Deploy', exact: true }).click()
  await until(() => sender !== null)
  const emit = (body: object) => sender?.(body)
  const lines = [
    'Pulling web (nginx:alpine)...',
    ' Image nginx:alpine Pulled',
    ' Network shop_default Created',
    ' Container shop-web-1 Created',
    ' Container shop-web-1 Started',
  ]
  for (const [i, line] of lines.entries()) {
    await pause(700)
    emit({ seq: i + 1, type: 'LINE', line })
  }
  await pause(500)
  routes[`GET ${MANAGED}/${NEW_DEPLOYMENT}`] = { body: { ...shop, status: 'RUNNING', lastDeployedAt: now } }
  emit({ seq: lines.length + 1, type: 'STATUS', status: 'DONE' })
  await page.getByLabel('Deployment output').getByRole('status').getByText('Succeeded').waitFor()
  await pause(2000)

  const video = page.video()
  await context.close()
  problems.push(...unmocked)
  if (!video) throw new Error('Playwright did not record a video')
  const webm = await video.path()

  try {
    execFileSync('ffmpeg', ['-version'], { stdio: 'ignore' })
  } catch {
    console.warn('ffmpeg not found on PATH: skipped deploy-flow.gif (recording kept at ' + webm + ')')
    return
  }
  const gif = join(outDir, 'deploy-flow.gif')
  execFileSync('ffmpeg', [
    '-y', '-loglevel', 'error', '-i', webm,
    '-vf', 'fps=10,scale=960:-2:flags=lanczos,split[a][b];[a]palettegen=max_colors=64:stats_mode=diff[p];[b][p]paletteuse=dither=bayer:bayer_scale=5',
    '-loop', '0', gif,
  ])
  const size = statSync(gif).size
  console.log(`wrote docs/images/deploy-flow.gif (${(size / 1024).toFixed(0)} KB)`)
  if (size >= 1024 * 1024) throw new Error('deploy-flow.gif is 1 MB or larger; lower fps or width')
}

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

  await captureDeployFlow(browser, problems)

  await browser.close()
  if (problems.length > 0) throw new Error(`Unmocked API calls: ${[...new Set(problems)].join(', ')}`)
}

main().catch((error) => {
  console.error(error)
  process.exitCode = 1
})
