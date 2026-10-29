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

function defaultFor(method: string, path: string): MockResponse | undefined {
  if (method !== 'GET') return undefined
  if (/^\/api\/hosts\/[^/]+\/stats\/history$/.test(path)) return { body: EMPTY_HISTORY }
  if (/^\/api\/hosts\/[^/]+\/proxy$/.test(path)) return { body: NO_PROXY }
  return undefined
}

/**
 * Intercepts every `/api/**` request of a test. Specs register mocks;
 * a call without a mock answers 501 and fails the test afterwards.
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

      // Predicate, not a glob: '**/api/**' would also match source files such as /src/shared/api/client.ts.
      await page.route(
        (url) => url.pathname.startsWith('/api/'),
        async (route) => {
          const request = route.request()
          const id = key(request.method(), new URL(request.url()).pathname)
          const mocked = mocks.get(id) ?? defaultFor(request.method(), new URL(request.url()).pathname)
          if (!mocked) {
            unmocked.push(id)
            await route.fulfill({ status: 501, json: { error: `No e2e mock registered for ${id}` } })
            return
          }
          await route.fulfill({ status: mocked.status ?? 200, json: mocked.body ?? null })
        },
      )

      await use({
        mock: (method, path, response = {}) => {
          mocks.set(key(method, path), response)
        },
      })

      expect(unmocked, 'API calls without a registered mock').toEqual([])
    },
    { auto: true },
  ],
})

export { expect }
