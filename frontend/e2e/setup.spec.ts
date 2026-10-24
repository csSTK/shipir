import { expect, test } from './fixtures/api.ts'

const initial = { username: 'admin', role: 'ADMIN', setupRequired: true, setupStep: 'PASSWORD' }
const needsEnvironment = { username: 'admin', role: 'ADMIN', setupRequired: true, setupStep: 'ENVIRONMENT' }
const ready = { username: 'admin', role: 'ADMIN', setupRequired: false, setupStep: null }
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
  api.mock('GET', '/api/auth/me', { body: needsEnvironment })
  await submit.click()
  await expect(page.getByRole('heading', { name: 'Add your first environment' })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Set your password' })).toHaveCount(0)
})

test('environment step: add Local -> app shown', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', { body: needsEnvironment })
  await page.goto('/')
  await expect(page.getByRole('heading', { name: 'Add your first environment' })).toBeVisible()
  await page.getByLabel('Name').fill('Local')

  api.mock('POST', '/api/hosts', {
    status: 201,
    body: { id: '1', name: 'Local', connectionType: 'LOCAL', host: null, port: null, tls: false, createdAt: '2026-01-01T00:00:00Z' },
  })
  api.mock('GET', '/api/auth/me', { body: ready })
  api.mock('GET', '/api/hosts/overview', { body: [] })
  await page.getByRole('button', { name: 'Continue' }).click()
  await expect(page.getByRole('link', { name: 'Shipir' })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Add your first environment' })).toHaveCount(0)
})

test('reload during the environment step lands on step 2', async ({ page, api }) => {
  // No /api/hosts/overview mock: the fixture fails the test if the app requests it.
  api.mock('GET', '/api/auth/me', { body: needsEnvironment })
  await page.goto('/')
  await expect(page.getByRole('heading', { name: 'Add your first environment' })).toBeVisible()

  await page.reload()

  await expect(page.getByRole('heading', { name: 'Add your first environment' })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Set your password' })).toHaveCount(0)
  await expect(page.getByRole('listitem').filter({ hasText: 'Environment' }).first()).toHaveAttribute('aria-current', 'step')
})

test('environment step: connection failure is shown and the user stays on step 2', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', { body: needsEnvironment })
  api.mock('POST', '/api/hosts', {
    status: 400,
    body: { code: 'CONNECTION_FAILED', message: 'failed', details: ['UNREACHABLE'] },
  })
  await page.goto('/')
  await page.getByLabel('Name').fill('Local')
  await page.getByRole('button', { name: 'Continue' }).click()

  await expect(page.getByRole('alert')).toContainText('Could not reach the Docker host')
  await expect(page.getByRole('heading', { name: 'Add your first environment' })).toBeVisible()
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
  await expect(page.getByRole('link', { name: 'Shipir' })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Set your password' })).toHaveCount(0)
})
