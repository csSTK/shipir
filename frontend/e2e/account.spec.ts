import { expect, test } from './fixtures/api.ts'

test('opens settings from the user menu, changes password, toggles a preference', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', { body: { username: 'pat', role: 'OPERATOR', setupRequired: false } })
  api.mock('GET', '/api/hosts/overview', { body: [] })
  api.mock('GET', '/api/account/tokens', { body: [] })
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

test('empty cards use empty states and the password checklist is spaced (layout check instead of a screenshot)', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', { body: { username: 'pat', role: 'OPERATOR', setupRequired: false } })
  api.mock('GET', '/api/hosts/overview', { body: [] })
  api.mock('GET', '/api/account/tokens', { body: [] })
  api.mock('GET', '/api/notifications/preferences', { body: [] })
  await page.setViewportSize({ width: 375, height: 800 })
  await page.goto('/account')

  const notifications = page.getByRole('region', { name: 'Notifications' })
  await expect(notifications.getByText('No notification settings yet')).toBeVisible()
  const tokens = page.getByRole('region', { name: 'API tokens' })
  await expect(tokens.getByRole('button', { name: 'Create token' })).toBeVisible()

  const rows = page.locator('.account__rules li')
  const first = await rows.nth(0).boundingBox()
  const second = await rows.nth(1).boundingBox()
  expect(first!.height).toBeGreaterThanOrEqual(28)
  expect(second!.y - (first!.y + first!.height)).toBeGreaterThan(0)

  const submit = await page.getByRole('button', { name: 'Change password' }).boundingBox()
  const card = await page.locator('.account__form').boundingBox()
  expect(submit!.width).toBeLessThan(card!.width / 2)

  // Enable 2FA is a normal-sized, right-aligned action, not a full-width bar.
  const enable = await page.getByRole('button', { name: 'Enable two-factor authentication' }).boundingBox()
  const twoFa = await page.getByRole('region', { name: 'Two-factor authentication' }).boundingBox()
  expect(enable!.width).toBeLessThan(twoFa!.width - 40)
  expect(enable!.x + enable!.width).toBeGreaterThan(twoFa!.x + twoFa!.width - 40)

  // The curl example scrolls inside its block instead of widening the page.
  const curl = page.locator('pre.otsecret-code')
  const overflow = await curl.evaluate((el) => getComputedStyle(el).overflowX)
  expect(overflow).toBe('auto')
  const scrollWidth = await page.evaluate(() => document.documentElement.scrollWidth)
  expect(scrollWidth).toBeLessThanOrEqual(375)
})
