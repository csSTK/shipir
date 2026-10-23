import { expect, test } from './fixtures/api.ts'

const initial = { username: 'admin', role: 'ADMIN', setupRequired: true }
const ready = { username: 'admin', role: 'ADMIN', setupRequired: false }
const GOOD = 'Abcdefghijklm1!'

test('login -> setup screen -> valid password -> app shown', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', { status: 401 })
  api.mock('POST', '/api/auth/login', { body: initial })
  await page.goto('/')
  await page.getByLabel('Username').fill('admin')
  await page.getByLabel('Password').fill('initial')
  await page.getByRole('button', { name: 'Sign in' }).click()

  await expect(page.getByRole('heading', { name: 'Set your password' })).toBeVisible()
  const submit = page.getByRole('button', { name: 'Set password' })
  await page.getByLabel('Current password').fill('initial')
  await page.getByLabel('New password', { exact: true }).fill('short')
  await page.getByLabel('Confirm new password').fill('short')
  await expect(submit).toBeDisabled()

  await page.getByLabel('New password', { exact: true }).fill(GOOD)
  await page.getByLabel('Confirm new password').fill(GOOD)
  await expect(submit).toBeEnabled()

  api.mock('POST', '/api/auth/password', { status: 204 })
  api.mock('GET', '/api/auth/me', { body: ready })
  api.mock('GET', '/api/hosts/overview', { body: [] })
  await submit.click()
  await expect(page.getByRole('heading', { name: 'Shipir' })).toBeVisible()
})

test('shows the server error for a wrong current password', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', { body: initial })
  api.mock('POST', '/api/auth/password', { status: 400, body: { code: 'INVALID_CURRENT_PASSWORD' } })
  await page.goto('/')
  await page.getByLabel('Current password').fill('nope')
  await page.getByLabel('New password', { exact: true }).fill(GOOD)
  await page.getByLabel('Confirm new password').fill(GOOD)
  await page.getByRole('button', { name: 'Set password' }).click()

  await expect(page.getByRole('alert')).toHaveText('The current password is incorrect.')
})

test('reload with setupRequired=false shows no setup screen', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', { body: ready })
  api.mock('GET', '/api/hosts/overview', { body: [] })
  await page.goto('/')
  await expect(page.getByRole('heading', { name: 'Shipir' })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Set your password' })).toHaveCount(0)
})
