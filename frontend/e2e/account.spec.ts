import { expect, test } from './fixtures/api.ts'

test('opens settings from the user menu, changes password, toggles a preference', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', { body: { username: 'pat', role: 'OPERATOR', setupRequired: false } })
  api.mock('GET', '/api/hosts/overview', { body: [] })
  api.mock('GET', '/api/notifications/preferences', {
    body: [
      { type: 'HOST_DOWN', inAppEnabled: true },
      { type: 'HOST_UP', inAppEnabled: false },
    ],
  })
  api.mock('PUT', '/api/notifications/preferences', { status: 204 })
  api.mock('POST', '/api/auth/password', { status: 204 })
  await page.goto('/')

  await page.getByRole('button', { name: /pat/ }).click()
  await page.getByRole('menuitem', { name: 'Settings' }).click()
  await expect(page).toHaveURL(/\/account$/)
  await expect(page.getByRole('main').getByText('OPERATOR')).toBeVisible()

  await page.getByLabel('Current password').fill('old')
  await page.getByLabel('New password', { exact: true }).fill('Abcdefghijklm1!')
  await page.getByLabel('Confirm new password').fill('Abcdefghijklm1!')
  await page.getByRole('button', { name: 'Change password' }).click()
  await expect(page.getByText('Password changed.')).toBeVisible()

  const up = page.getByRole('switch', { name: /host up/i })
  await expect(up).not.toBeChecked()
  await up.click()
  await expect(up).toBeChecked()
})
