import { expect, test } from './api.ts'

const callApi = (page: import('@playwright/test').Page, path: string) =>
  page.evaluate(async (p) => {
    const response = await fetch(p)
    return { status: response.status, body: await response.json() }
  }, path)

test('serves a registered mock to the page', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', { status: 401 })
  api.mock('GET', '/api/hosts', { status: 200, body: [{ id: 'h1', name: 'local' }] })
  await page.goto('/')

  const result = await callApi(page, '/api/hosts?x=1')

  expect(result).toEqual({ status: 200, body: [{ id: 'h1', name: 'local' }] })
})

test('fails the test when an /api call has no mock', async ({ page, api }) => {
  test.fail()
  api.mock('GET', '/api/auth/me', { status: 401 })
  await page.goto('/')

  await callApi(page, '/api/containers')
})
