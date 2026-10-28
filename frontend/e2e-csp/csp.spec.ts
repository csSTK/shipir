import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { expect, test } from '../e2e/fixtures/api.ts'

// The policy under test is the one nginx sends: read it from the template, not a copy.
const template = readFileSync(fileURLToPath(new URL('../nginx/security-headers.inc.template', import.meta.url)), 'utf8')
const csp = /add_header Content-Security-Policy "([^"]+)"/.exec(template)?.[1]
if (!csp) throw new Error('Content-Security-Policy not found in nginx/security-headers.inc.template')

test('connect-src is same-origin only (the STOMP /ws connection is same-origin)', () => {
  const connectSrc = /(?:^|;)\s*connect-src\s+([^;]+)/.exec(csp)?.[1]?.trim()
  // No ws:/wss: scheme sources: 'self' already covers same-origin WebSocket in current browsers.
  expect(connectSrc).toBe("'self'")
})

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
  containers: { running: 0, stopped: 0, healthy: 0, unhealthy: 0 },
  images: 0,
  volumes: 0,
}
const base = '/api/hosts/a/managed-deployments'
const deployment = {
  id: 's1',
  name: 'shop',
  status: 'STOPPED',
  source: 'FILE',
  lastDeployedAt: null,
  createdAt: '2026-10-01T10:00:00Z',
  yaml: 'services:\n  web:\n    image: nginx',
  envKeys: [],
}

test('pages, Monaco editors and workers run under the nginx CSP without violations', async ({ page, context, api }) => {
  // Same effect as nginx: every non-API response (page, scripts, workers, styles) carries the policy.
  await context.route(
    (url) => !url.pathname.startsWith('/api/'),
    async (route) => {
      const response = await route.fetch()
      await route.fulfill({ response, headers: { ...response.headers(), 'content-security-policy': csp } })
    },
  )

  const violations: string[] = []
  await page.addInitScript(() => {
    document.addEventListener('securitypolicyviolation', (e) => {
      ;(window as unknown as { __csp: string[] }).__csp ??= []
      ;(window as unknown as { __csp: string[] }).__csp.push(`${e.violatedDirective} ${e.blockedURI}`)
    })
  })
  page.on('console', (m) => {
    if (/content security policy|refused to/i.test(m.text())) violations.push(m.text())
  })
  page.on('pageerror', (e) => violations.push(`pageerror: ${e.message}`))

  api.mock('GET', '/api/auth/me', { body: { username: 'u', role: 'OPERATOR', setupRequired: false } })
  api.mock('GET', '/api/hosts/overview', { body: [host] })
  api.mock('GET', '/api/hosts/a/deployments', { body: [] })
  api.mock('GET', base, { body: [deployment] })
  api.mock('GET', `${base}/s1`, { body: deployment })
  api.mock('GET', `${base}/s1/versions`, { body: [] })
  api.mock('GET', `${base}/s1/files`, { body: [] })
  api.mock('GET', `${base}/s1/webhook`, { body: { enabled: false, createdAt: null, rotatedAt: null } })
  api.mock('GET', `${base}/s1/uptime-checks`, { body: [] })
  api.mock('GET', `${base}/s1/services`, { body: { services: ['web'], profiles: [] } })
  api.mock('GET', '/api/templates', { body: [] })

  await page.goto('/')
  await expect(page.getByRole('link', { name: 'Shipir' })).toBeVisible()
  await expect(page.getByText('Local').first()).toBeVisible()

  // YAML editor (Monaco + monaco-yaml worker) in the add dialog
  await page.goto('/environments/a/deployments')
  await page.getByRole('button', { name: 'Add deployment' }).click()
  await page.getByRole('radio', { name: 'YAML' }).click()
  await expect(page.locator('.monaco-editor').first()).toBeVisible({ timeout: 30_000 })
  await page.keyboard.press('Escape')

  // Detail page with the draft editor
  await page.goto('/environments/a/deployments/s1')
  await expect(page.getByRole('heading', { name: 'shop' })).toBeVisible()
  await page.getByRole('button', { name: 'Edit' }).first().click()
  await expect(page.locator('.monaco-editor').first()).toBeVisible({ timeout: 30_000 })
  await page.waitForTimeout(1000) // workers start after the editor is up

  const reported = await page.evaluate(() => (window as unknown as { __csp?: string[] }).__csp ?? [])
  expect([...violations, ...reported], 'CSP violations').toEqual([])
})

test('same-origin WebSocket (STOMP /ws) is allowed by connect-src self', async ({ page, context, api }) => {
  await context.route(
    (url) => !url.pathname.startsWith('/api/'),
    async (route) => {
      const response = await route.fetch()
      await route.fulfill({ response, headers: { ...response.headers(), 'content-security-policy': csp } })
    },
  )
  await page.addInitScript(() => {
    document.addEventListener('securitypolicyviolation', (e) => {
      ;(window as unknown as { __csp: string[] }).__csp ??= []
      ;(window as unknown as { __csp: string[] }).__csp.push(`${e.violatedDirective} ${e.blockedURI}`)
    })
  })
  api.mock('GET', '/api/auth/me', { body: { username: 'u', role: 'OPERATOR', setupRequired: false } })
  api.mock('GET', '/api/hosts/overview', { body: [host] })

  await page.goto('/')
  await expect(page.getByRole('link', { name: 'Shipir' })).toBeVisible()

  // Same URL the app builds in shared/realtime/stomp.ts. The socket is not answered (no backend here);
  // what matters is that the browser does not refuse it under the policy.
  const outcome = await page.evaluate(
    () =>
      new Promise<string>((resolve) => {
        try {
          const ws = new WebSocket(`${location.protocol === 'https:' ? 'wss:' : 'ws:'}//${location.host}/ws`)
          ws.onopen = () => {
            ws.close()
            resolve('open')
          }
          ws.onerror = () => resolve('error')
          ws.onclose = () => resolve('closed')
        } catch (e) {
          resolve(`threw ${(e as Error).name}`)
        }
      }),
  )
  const reported = await page.evaluate(() => (window as unknown as { __csp?: string[] }).__csp ?? [])
  expect(outcome, 'WebSocket construction').not.toMatch(/^threw/)
  expect(reported.filter((v) => v.startsWith('connect-src')), 'connect-src violations').toEqual([])
})
