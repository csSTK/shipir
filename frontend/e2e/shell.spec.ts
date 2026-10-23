import { expect, test } from './fixtures/api.ts'

const me = (role: string) => ({ body: { username: 'pat', role, setupRequired: false } })

test('admin navigates via the sidebar and opens settings from the user menu', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', me('ADMIN'))
  api.mock('GET', '/api/hosts/overview', { body: [] })
  api.mock('GET', '/api/settings/retention', { body: { auditLogDays: 90, notificationDays: 30 } })
  await page.goto('/')

  const nav = page.getByRole('navigation', { name: 'Main' })
  await nav.getByRole('link', { name: 'Retention' }).click()
  await expect(page).toHaveURL(/\/settings\/retention$/)
  await expect(page.getByRole('heading', { name: 'Retention' })).toBeVisible()
  await expect(nav.getByRole('link', { name: 'Retention' })).toHaveAttribute('aria-current', 'page')

  await page.getByRole('button', { name: /pat/ }).click()
  await page.getByRole('menuitem', { name: 'Settings' }).click()
  await expect(page).toHaveURL(/\/account$/)
})

test('viewer has no settings group and is not allowed on /settings/*', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', me('VIEWER'))
  await page.goto('/settings/audit-log')
  await expect(page.getByText('You are not allowed to view this page.')).toBeVisible()
  await expect(page.getByRole('group', { name: 'Settings' })).toHaveCount(0)
})
