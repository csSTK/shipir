import { expect, test } from './fixtures/api.ts'

const admin = { username: 'admin', role: 'ADMIN', setupRequired: false }

test('signs in and out', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', { status: 401 })
  api.mock('POST', '/api/auth/login', { body: admin })
  api.mock('GET', '/api/hosts/overview', { body: [] })
  await page.goto('/')

  await page.getByLabel('Username').fill('admin')
  await page.getByLabel('Password').fill('correct horse')
  await page.getByRole('button', { name: 'Sign in' }).click()
  await expect(page.getByRole('link', { name: 'Shipir' })).toBeVisible()

  api.mock('POST', '/api/auth/logout', { status: 204 })
  await page.getByRole('button', { name: /admin/ }).click()
  await page.getByRole('menuitem', { name: 'Sign out' }).click()
  await expect(page.getByLabel('Username')).toBeVisible()
})

test('shows a generic error for a wrong password and keeps the username', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', { status: 401 })
  api.mock('POST', '/api/auth/login', { status: 401, body: { code: 'INVALID_CREDENTIALS' } })
  await page.goto('/')

  await page.getByLabel('Username').fill('admin')
  await page.getByLabel('Password').fill('wrong')
  await page.getByRole('button', { name: 'Sign in' }).click()

  await expect(page.getByRole('alert')).toHaveText('Invalid username or password.')
  await expect(page.getByLabel('Username')).toHaveValue('admin')
})
