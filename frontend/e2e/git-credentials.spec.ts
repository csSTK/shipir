import { expect, test } from './fixtures/api.ts'

const cred = {
  id: 'g1',
  name: 'GitHub work',
  username: 'octocat',
  tokenHint: '…a1b2',
  createdAt: '2026-10-23T10:00:00Z',
  updatedAt: '2026-10-23T10:00:00Z',
}
const user = (role: string) => ({ username: 'u', role, setupRequired: false })

test('admin adds a credential and tests the connection', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', { body: user('ADMIN') })
  api.mock('GET', '/api/git/credentials', { body: [cred] })
  api.mock('POST', '/api/git/credentials', { status: 201, body: { ...cred, id: 'g2', name: 'Gitea' } })
  api.mock('POST', '/api/git/credentials/g1/test', { body: { status: 'AUTH_FAILED' } })
  await page.goto('/settings/git')

  await expect(page.getByText('GitHub work')).toBeVisible()
  await page.getByRole('button', { name: 'Add credential' }).click()
  await page.getByLabel('Name', { exact: true }).fill('Gitea')
  await page.getByLabel('Username', { exact: true }).fill('me')
  await page.getByLabel('Access token').fill('super-secret-pat')
  await page.getByRole('button', { name: 'Save' }).click()
  await expect(page.getByRole('dialog')).toHaveCount(0)
  await expect(page.getByText('super-secret-pat')).toHaveCount(0)

  await page.getByRole('button', { name: 'Test GitHub work' }).click()
  await page.getByLabel('Repository URL').fill('https://github.com/a/b.git')
  await page.getByRole('button', { name: 'Run test' }).click()
  await expect(page.getByText('The server rejected the username or token.')).toBeVisible()
})

test('deleting a credential in use shows the conflict', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', { body: user('ADMIN') })
  api.mock('GET', '/api/git/credentials', { body: [cred] })
  api.mock('DELETE', '/api/git/credentials/g1', { status: 409, body: { code: 'CREDENTIAL_IN_USE', message: 'in use' } })
  await page.goto('/settings/git')
  await page.getByRole('button', { name: 'Delete GitHub work' }).click()
  await page.getByRole('dialog').getByRole('button', { name: 'Delete' }).click()
  await expect(page.getByText(/still used by one or more deployments/)).toBeVisible()
})

test('operator cannot reach the page', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', { body: user('OPERATOR') })
  await page.goto('/settings/git')
  await expect(page.getByText('You are not allowed to view this page.')).toBeVisible()
})
