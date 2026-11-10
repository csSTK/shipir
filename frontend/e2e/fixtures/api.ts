import { test as base, expect } from '@playwright/test'

type HttpMethod = 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'

interface MockResponse<T = unknown> {
  status?: number
  body?: T
}

export interface ApiMock {
  /** Registers a JSON response for `method` + `path` (path without query string). */
  mock<T>(method: HttpMethod, path: string, response?: MockResponse<T>): void
}

const key = (method: string, path: string) => `${method.toUpperCase()} ${path}`

/** Empty usage history: the container details page and the dashboard fetch it on every visit; specs override per host. */
const EMPTY_HISTORY = { range: '24h', from: '2026-10-24T10:00:00Z', to: '2026-10-25T10:00:00Z', bucketSeconds: 60, seriesTotal: 0, truncated: false, series: [] }

/** No reverse proxy detected: the add-deployment wizard asks for it whenever the dialog is open; specs override. */
const NO_PROXY = { visible: true, proxy: null, container: null, network: null }

/** Raw proxy configuration (ADMIN, advanced mode on the domains page): nothing generated, no custom content, no history; specs override. */
const EMPTY_PROXY_CONFIG = { type: 'TRAEFIK', managed: [], customFileName: 'custom.yml', custom: '', customUpdatedAt: null }

function defaultFor(method: string, path: string): MockResponse | undefined {
  if (method !== 'GET') return undefined
  if (/^\/api\/hosts\/[^/]+\/stats\/history$/.test(path)) return { body: EMPTY_HISTORY }
  if (/^\/api\/hosts\/[^/]+\/proxy$/.test(path)) return { body: NO_PROXY }
  if (/^\/api\/hosts\/[^/]+\/proxy\/config$/.test(path)) return { body: EMPTY_PROXY_CONFIG }
  if (/^\/api\/hosts\/[^/]+\/proxy\/config\/custom\/versions$/.test(path)) return { body: [] }
  return undefined
}

/**
 * The card endpoints of a managed deployment's detail page: services, uptime checks, webhook, versions and config files.
 * Answers with empty defaults (`versions` is the spec's data). Call it before the spec's own mocks: a later mock() for the same path wins.
 * A new card's endpoint goes here, so specs need no edit for it.
 */
export function mockDeploymentCards(api: ApiMock, deployment: string, versions: unknown[] = []): void {
  api.mock('GET', `${deployment}/services`, { body: { services: ['web'], profiles: [] } })
  api.mock('GET', `${deployment}/uptime-checks`, { body: [] })
  api.mock('GET', `${deployment}/webhook`, { body: { enabled: false, createdAt: null, rotatedAt: null } })
  api.mock('GET', `${deployment}/versions`, { body: versions })
  api.mock('GET', `${deployment}/files`, { body: [] })
}

/**
 * Monaco editor worker setup errors. They only show up as page errors or console messages, not as failed requests,
 * so every test checks them. Narrow on purpose: an app error that merely says "Cannot read properties of undefined"
 * is not a Monaco error; Monaco's own failure is "reading 'toUrl'".
 */
const MONACO_ERROR = /module is not defined|reading 'toUrl'|Could not create web worker/i

/**
 * Intercepts every `/api/**` request of a test. Specs register mocks;
 * a call without a mock answers 501 and fails the test afterwards.
 * Also fails the test on any uncaught page error (shipr-k31n.60) and on Monaco worker errors (console errors or warnings) in any spec.
 */
export const test = base.extend<{ api: ApiMock }>({
  api: [
    async ({ page }, use) => {
      // The shell polls the unread badge on every page; default to none, specs override.
      const mocks = new Map<string, MockResponse>([
        [key('GET', '/api/notifications/unread-count'), { body: { count: 0 } }],
        // The assistant dock in the shell asks for pending chat actions on every page; default to none, specs override.
        [key('GET', '/api/ai/sessions/pending-count'), { body: { count: 0 } }],
        // The login page asks for it; default to single sign-on off, specs override.
        [key('GET', '/api/auth/oidc/config'), { body: { enabled: false, displayName: null, localLoginEnabled: true } }],
        // OPERATOR logins ask whether operator exec is enabled; default to off, specs override.
        [key('GET', '/api/exec/capabilities'), { body: { operatorEnabled: false } }],
        // Where an OPERATOR may open consoles on the host (query hostId); default none, specs override.
        [key('GET', '/api/exec/access'), { body: { hostOperable: false, operableProjects: [] } }],
      ])
      const unmocked: string[] = []
      const monacoErrors: string[] = []
      const pageErrors: string[] = []
      page.on('pageerror', (e) => {
        pageErrors.push(e.message)
        monacoErrors.push(e.message)
      })
      page.on('console', (m) => {
        if (m.type() === 'error' || m.type() === 'warning') monacoErrors.push(m.text())
      })

      // Handlers still running; awaited at the end so a late call is counted before the check (see below).
      const inFlight = new Set<Promise<void>>()
      // Predicate, not a glob: '**/api/**' would also match source files such as /src/shared/api/client.ts.
      await page.route(
        (url) => url.pathname.startsWith('/api/'),
        (route) => {
          const handled = handle(route).finally(() => inFlight.delete(handled))
          inFlight.add(handled)
          return handled
        },
      )
      const handle = async (route: import('@playwright/test').Route) => {
          const request = route.request()
          const id = key(request.method(), new URL(request.url()).pathname)
          const mocked = mocks.get(id) ?? defaultFor(request.method(), new URL(request.url()).pathname)
          // fulfill() throws when the page closed meanwhile; that must not fail a test, only the recorded `unmocked` does.
          if (!mocked) {
            unmocked.push(id)
            await route.fulfill({ status: 501, json: { error: `No e2e mock registered for ${id}` } }).catch(() => {})
            return
          }
          await route.fulfill({ status: mocked.status ?? 200, json: mocked.body ?? null }).catch(() => {})
      }

      await use({
        mock: (method, path, response = {}) => {
          mocks.set(key(method, path), response)
        },
      })

      // Requests the app fired just before the test ended may still be inside their route handler: wait for those
      // handlers ourselves, then drop the routes without rethrowing handler errors (`behavior: 'wait'` rethrows them
      // and failed unrelated tests when the page closed under load). Unmocked calls are recorded before fulfilling.
      // Limit: a request the browser has not yet handed to the route (fired in the same tick the test ends) cannot be
      // seen by any hook, so a spec must await what it fires; the guard covers every call the test body waited for.
      await Promise.allSettled([...inFlight])
      await page.unrouteAll({ behavior: 'ignoreErrors' })

      expect(unmocked, 'API calls without a registered mock').toEqual([])
      expect(pageErrors, 'Uncaught page errors').toEqual([])
      expect(monacoErrors.filter((m) => MONACO_ERROR.test(m)), 'Monaco worker errors').toEqual([])
    },
    { auto: true },
  ],
})

export { expect }
