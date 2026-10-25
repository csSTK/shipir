import { expect, test } from './fixtures/api.ts'

const me = (role: string) => ({ body: { username: 'pat', role, setupRequired: false } })

test('admin navigates via the sidebar and opens settings from the user menu', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', me('ADMIN'))
  api.mock('GET', '/api/hosts/overview', { body: [] })
  api.mock('GET', '/api/settings/retention', { body: { auditLogDays: 90, notificationDays: 30 } })
  api.mock('GET', '/api/account/tokens', { body: [] })
  api.mock('GET', '/api/notifications/preferences', { body: [] })
  await page.goto('/')

  const nav = page.getByRole('navigation', { name: 'Main' })
  await nav.getByRole('link', { name: 'Retention' }).click()
  await expect(page).toHaveURL(/\/settings\/retention$/)
  await expect(page.getByRole('heading', { name: 'Retention' })).toBeVisible()
  await expect(nav.getByRole('link', { name: 'Retention' })).toHaveAttribute('aria-current', 'page')

  await page.getByRole('button', { name: /pat/ }).click()
  await page.getByRole('menuitem', { name: 'Settings' }).click()
  await expect(page).toHaveURL(/\/account$/)
  // Wait for the page's own request to be answered so it cannot fire after the test ends.
  await expect(page.getByRole('heading', { name: 'Change password' })).toBeVisible()
})

test('viewer has no settings group and is not allowed on /settings/*', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', me('VIEWER'))
  await page.goto('/settings/audit-log')
  await expect(page.getByText('You are not allowed to view this page.')).toBeVisible()
  await expect(page.getByRole('group', { name: 'Settings' })).toHaveCount(0)
})

test('narrow screen: navigation opens as a drawer and closes after navigating', async ({ page, api }) => {
  await page.setViewportSize({ width: 375, height: 700 })
  api.mock('GET', '/api/auth/me', me('VIEWER'))
  api.mock('GET', '/api/hosts/overview', { body: [] })
  api.mock('GET', '/api/notifications', { body: { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 } })
  await page.goto('/')

  await expect(page.getByRole('navigation', { name: 'Main' })).toHaveCount(0)
  await page.getByRole('button', { name: 'Navigation' }).click()
  const nav = page.getByRole('navigation', { name: 'Main' })
  await expect(nav).toBeVisible()
  await nav.getByRole('link', { name: /^Notifications/ }).click()
  await expect(page).toHaveURL(/\/notifications$/)
  await expect(page.getByRole('navigation', { name: 'Main' })).toHaveCount(0)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
})

test('environment switcher lists environments with status and navigates', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', me('VIEWER'))
  api.mock('GET', '/api/hosts/overview', {
    body: [
      { id: 'h1', name: 'Prod', status: 'UP', connectionType: 'LOCAL', endpoint: 'local', error: null },
      { id: 'h2', name: 'Staging', status: 'DOWN', connectionType: 'TCP', endpoint: 'x', error: 'TIMEOUT' },
    ],
  })
  api.mock('GET', '/api/hosts/h2/images', { body: [] })
  api.mock('GET', '/api/hosts/h1/images', { body: [] })
  await page.goto('/environments/h2/images')
  await page.getByRole('button', { name: /^Environment:/ }).click()
  await page.getByRole('menuitem', { name: 'Prod' }).click()
  await expect(page).toHaveURL(/\/environments\/h1\/images$/)
  // The images page fetches its list after the URL changes; wait for its answer so the request cannot outlive the test.
  await expect(page.getByText('No images in this environment.')).toBeVisible()
})
