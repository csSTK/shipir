import { expect, test } from './api.ts'

// goto waits for DOMContentLoaded only: 'load' waits for Vite's whole module graph, which times out on a loaded machine
// (first visit of a worker) and would let the test.fail() meta tests below pass for the wrong reason.
const callApi = (page: import('@playwright/test').Page, path: string) =>
  page.evaluate(async (p) => {
    const response = await fetch(p)
    return { status: response.status, body: await response.json() }
  }, path)

test('serves a registered mock to the page', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', { status: 401 })
  api.mock('GET', '/api/hosts', { status: 200, body: [{ id: 'h1', name: 'local' }] })
  await page.goto('/', { waitUntil: 'domcontentloaded' })

  const result = await callApi(page, '/api/hosts?x=1')

  expect(result).toEqual({ status: 200, body: [{ id: 'h1', name: 'local' }] })
})

test('fails the test on a Monaco worker page error, without a spec guard', async ({ page, api }) => {
  test.fail()
  api.mock('GET', '/api/auth/me', { status: 401 })
  await page.goto('/', { waitUntil: 'domcontentloaded' })

  // The fixture's check at the end reports the Monaco error, so the test fails (test.fail() turns that into a pass).
  const error = page.waitForEvent('pageerror')
  await page.evaluate(() => setTimeout(() => { throw new Error('Could not create web worker(s)') }, 0))
  await error
})

test('fails the test on a Monaco worker console error, without a spec guard', async ({ page, api }) => {
  test.fail()
  api.mock('GET', '/api/auth/me', { status: 401 })
  await page.goto('/', { waitUntil: 'domcontentloaded' })

  const message = page.waitForEvent('console', (m) => m.type() === 'error')
  await page.evaluate(() => console.error('module is not defined'))
  await message
})

test('ignores unrelated app errors that only share the Cannot read properties wording', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', { status: 401 })
  await page.goto('/', { waitUntil: 'domcontentloaded' })

  const error = page.waitForEvent('pageerror')
  await page.evaluate(() => setTimeout(() => { throw new TypeError("Cannot read properties of undefined (reading 'name')") }, 0))
  await error
})

test('fails the test when an /api call has no mock', async ({ page, api }) => {
  test.fail()
  api.mock('GET', '/api/auth/me', { status: 401 })
  await page.goto('/', { waitUntil: 'domcontentloaded' })

  // The call itself does not throw (the fixture answers 501); the failure comes from the fixture's check at the end.
  const result = await callApi(page, '/api/containers')

  expect(result.status).toBe(501)
})
