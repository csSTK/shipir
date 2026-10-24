import { expect, test } from './fixtures/api.ts'

const admin = { username: 'admin', role: 'ADMIN', setupRequired: false }
const NEW_PASSWORD = 'Abcdefghijklm1!'

test.beforeEach(({ api }) => {
  api.mock('GET', '/api/auth/me', { status: 401 })
})

test('resets the password with a code and signs in again', async ({ page, api }) => {
  api.mock('POST', '/api/auth/password-reset/request', { status: 202 })
  api.mock('POST', '/api/auth/password-reset/confirm', { status: 204 })
  api.mock('POST', '/api/auth/login', { body: admin })
  api.mock('GET', '/api/hosts/overview', { body: [] })
  await page.goto('/')

  await page.getByRole('button', { name: 'Forgot password?' }).click()
  await page.getByLabel('Username').fill('admin')
  await page.getByRole('button', { name: 'Request code' }).click()
  await expect(page.getByText('If the account exists, a code has been issued.')).toBeVisible()

  await page.getByLabel('6-digit code').fill('123456')
  await page.getByLabel('New password', { exact: true }).fill(NEW_PASSWORD)
  await page.getByLabel('Confirm new password').fill(NEW_PASSWORD)
  await page.getByRole('button', { name: 'Reset password' }).click()

  await expect(page.getByRole('status')).toContainText('Your password was reset')
  await page.getByLabel('Username').fill('admin')
  await page.getByLabel('Password').fill(NEW_PASSWORD)
  await page.getByRole('button', { name: 'Sign in' }).click()
  await expect(page.getByRole('link', { name: 'Shipir' })).toBeVisible()
})

test('shows an error for an invalid code and offers a new one', async ({ page, api }) => {
  api.mock('POST', '/api/auth/password-reset/request', { status: 202 })
  api.mock('POST', '/api/auth/password-reset/confirm', { status: 400, body: { code: 'INVALID_RESET_CODE' } })
  await page.goto('/')

  await page.getByRole('button', { name: 'Forgot password?' }).click()
  await page.getByLabel('Username').fill('admin')
  await page.getByRole('button', { name: 'Request code' }).click()
  await page.getByLabel('6-digit code').fill('000000')
  await page.getByLabel('New password', { exact: true }).fill(NEW_PASSWORD)
  await page.getByLabel('Confirm new password').fill(NEW_PASSWORD)
  await page.getByRole('button', { name: 'Reset password' }).click()

  await expect(page.getByRole('alert')).toContainText('invalid or expired')
  await page.getByRole('button', { name: 'Request a new code' }).click()
  await expect(page.getByRole('button', { name: 'Request code' })).toBeVisible()
})

test('shows the same neutral message for an unknown username', async ({ page, api }) => {
  api.mock('POST', '/api/auth/password-reset/request', { status: 202 })
  await page.goto('/')

  await page.getByRole('button', { name: 'Forgot password?' }).click()
  await page.getByLabel('Username').fill('nobody-here')
  await page.getByRole('button', { name: 'Request code' }).click()
  await expect(page.getByText('If the account exists, a code has been issued.')).toBeVisible()
})
