import { expect, test } from './fixtures/api.ts'

const me = (role: string) => ({ body: { username: 'pat', role, setupRequired: false } })

const results = {
  truncated: false,
  results: [
    { type: 'DEPLOYMENT', id: 'd1', name: 'webapp', hostId: 'h1', hostName: 'Prod', managed: true, detail: 'RUNNING' },
    { type: 'CONTAINER', id: 'c1', name: 'webapp-1', hostId: 'h1', hostName: 'Prod', managed: false, detail: 'nginx:1' },
  ],
}

test('Ctrl+K searches and opens a container with the keyboard', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', me('VIEWER'))
  api.mock('GET', '/api/hosts/overview', { body: [] })
  api.mock('GET', '/api/search', { body: results })
  api.mock('GET', '/api/hosts/h1/containers/c1', { status: 404, body: { code: 'RESOURCE_NOT_FOUND', message: 'x' } })
  api.mock('GET', '/api/hosts/h1/containers', { body: [] })
  api.mock('GET', '/api/hosts/h1/deployments', { body: [] })
  await page.goto('/')
  await expect(page.getByRole('navigation', { name: 'Main' })).toBeVisible()

  await page.keyboard.press('Control+k')
  const input = page.getByRole('combobox')
  await expect(input).toBeFocused()
  await input.fill('webapp')
  await expect(page.getByRole('option', { name: /^webapp-1/ })).toBeVisible()
  await page.keyboard.press('ArrowDown')
  await page.keyboard.press('Enter')
  await expect(page).toHaveURL(/\/environments\/h1\/containers\/c1$/)
  await expect(page.getByRole('dialog', { name: 'Command palette' })).toHaveCount(0)
})

test('palette: navigation command, ADMIN-only entries, Escape, and a search error', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', me('ADMIN'))
  api.mock('GET', '/api/hosts/overview', { body: [] })
  api.mock('GET', '/api/search', { status: 500, body: { code: 'INTERNAL', message: 'x' } })
  api.mock('GET', '/api/audit-log', { body: { content: [], totalElements: 0, page: 0, size: 20 } })
  await page.goto('/')
  await expect(page.getByRole('navigation', { name: 'Main' })).toBeVisible()

  await page.keyboard.press('Control+k')
  await expect(page.getByRole('option', { name: 'Audit log' })).toBeVisible()
  await page.keyboard.press('Escape')
  await expect(page.getByRole('dialog', { name: 'Command palette' })).toHaveCount(0)

  await page.keyboard.press('Control+k')
  await page.getByRole('combobox').fill('zzzz')
  await expect(page.getByRole('alert')).toContainText('Search failed')

  await page.getByRole('combobox').fill('audit')
  await page.keyboard.press('Enter')
  await expect(page).toHaveURL(/\/settings\/audit-log$/)
})

test('viewer does not see settings commands', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', me('VIEWER'))
  api.mock('GET', '/api/hosts/overview', { body: [] })
  await page.goto('/')
  await expect(page.getByRole('navigation', { name: 'Main' })).toBeVisible()
  await page.keyboard.press('Control+k')
  await expect(page.getByRole('option', { name: 'Assistant' })).toBeVisible()
  await expect(page.getByRole('option', { name: 'Audit log' })).toHaveCount(0)
})
