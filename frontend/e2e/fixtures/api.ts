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

/**
 * Intercepts every `/api/**` request of a test. Specs register mocks;
 * a call without a mock answers 501 and fails the test afterwards.
 */
export const test = base.extend<{ api: ApiMock }>({
  api: [
    async ({ page }, use) => {
      const mocks = new Map<string, MockResponse>()
      const unmocked: string[] = []

      // Predicate, not a glob: '**/api/**' would also match source files such as /src/shared/api/client.ts.
      await page.route(
        (url) => url.pathname.startsWith('/api/'),
        async (route) => {
          const request = route.request()
          const id = key(request.method(), new URL(request.url()).pathname)
          const mocked = mocks.get(id)
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
