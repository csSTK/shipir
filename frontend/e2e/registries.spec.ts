import { expect, test } from './fixtures/api.ts'

const cred = {
  id: 'r1',
  name: 'GHCR',
  registryHost: 'ghcr.io',
  username: 'octocat',
  secretHint: '…a1b2',
  createdAt: '2026-10-24T10:00:00Z',
  updatedAt: '2026-10-24T10:00:00Z',
}
const user = (role: string) => ({ username: 'u', role, setupRequired: false })

test('admin adds a registry and tests the login', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', { body: user('ADMIN') })
  api.mock('GET', '/api/registries', { body: [cred] })
  api.mock('POST', '/api/registries', { status: 201, body: { ...cred, id: 'r2', name: 'Private' } })
  api.mock('POST', '/api/registries/r1/test', { body: { status: 'AUTH_FAILED' } })
  await page.goto('/settings/registries')

  await expect(page.getByText('GHCR', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: 'Add registry' }).click()
  await page.getByLabel('Name', { exact: true }).fill('Private')
  await page.getByLabel('Registry host').fill('registry.example.com')
  await page.getByLabel('Username').fill('me')
  await page.getByLabel('Password or token').fill('super-secret-pw')
  await page.getByRole('button', { name: 'Save' }).click()
  await expect(page.getByRole('dialog')).toHaveCount(0)
  await expect(page.getByText('super-secret-pw')).toHaveCount(0)

  await page.getByRole('button', { name: 'Test GHCR' }).click()
  await page.getByRole('button', { name: 'Run test' }).click()
  await expect(page.getByText('The registry rejected the username or secret.')).toBeVisible()
})

test('a taken host is shown inline', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', { body: user('ADMIN') })
  api.mock('GET', '/api/registries', { body: [cred] })
  api.mock('POST', '/api/registries', { status: 409, body: { code: 'REGISTRY_HOST_TAKEN', message: 'taken' } })
  await page.goto('/settings/registries')
  await page.getByRole('button', { name: 'Add registry' }).click()
  await page.getByLabel('Name', { exact: true }).fill('Other')
  await page.getByLabel('Registry host').fill('ghcr.io')
  await page.getByLabel('Username').fill('me')
  await page.getByLabel('Password or token').fill('pw')
  await page.getByRole('button', { name: 'Save' }).click()
  await expect(page.getByText('A credential for this registry host already exists.')).toBeVisible()
})

test('operator cannot reach the page', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', { body: user('OPERATOR') })
  await page.goto('/settings/registries')
  await expect(page.getByText('You are not allowed to view this page.')).toBeVisible()
})
