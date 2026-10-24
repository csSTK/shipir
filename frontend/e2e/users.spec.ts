import { expect, test } from './fixtures/api.ts'

const base = {
  passwordChangeRequired: false,
  createdAt: '2026-10-01T10:00:00Z',
  passwordChangedAt: '2026-10-03T10:00:00Z',
  disabledAt: null,
}
const users = [
  { ...base, id: 'u1', username: 'u', role: 'ADMIN', disabled: false },
  { ...base, id: 'u2', username: 'anna', role: 'OPERATOR', disabled: false },
]
const me = (role: string) => ({ username: 'u', role, setupRequired: false })

test('admin creates a user and sees the one-time password once', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', { body: me('ADMIN') })
  api.mock('GET', '/api/users', { body: users })
  api.mock('GET', '/api/settings/security', { body: { require2faForAdmins: false, updatedAt: null } })
  api.mock('POST', '/api/users', {
    status: 201,
    body: { id: 'u3', username: 'carl', role: 'VIEWER', oneTimePassword: 'Secret-Pass-12345!' },
  })
  await page.goto('/settings/users')
  await expect(page.getByText('anna', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Delete u' })).toHaveCount(0)

  await page.getByRole('button', { name: 'Add user' }).click()
  await page.getByRole('dialog').getByLabel('Username').fill('carl')
  await page.getByRole('dialog').getByLabel('Role').selectOption('VIEWER')
  await page.getByRole('button', { name: 'Create user' }).click()
  await expect(page.getByText('Secret-Pass-12345!')).toBeVisible()
  await page.getByRole('button', { name: 'Done' }).click()
  await expect(page.getByText('Secret-Pass-12345!')).toHaveCount(0)
})

test('role change on the last admin and deleting a user with history show inline errors', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', { body: me('ADMIN') })
  api.mock('GET', '/api/users', { body: users })
  api.mock('GET', '/api/settings/security', { body: { require2faForAdmins: false, updatedAt: null } })
  api.mock('PUT', '/api/users/u2/role', { status: 409, body: { code: 'LAST_ADMIN', message: 'x' } })
  api.mock('DELETE', '/api/users/u2', { status: 409, body: { code: 'USER_HAS_HISTORY', message: 'x' } })
  await page.goto('/settings/users')
  await page.getByLabel('Role for anna').selectOption('VIEWER')
  await expect(page.getByText(/last enabled admin|At least one enabled admin/)).toBeVisible()

  await page.getByRole('button', { name: 'Delete anna' }).click()
  await page.getByRole('dialog').getByRole('button', { name: 'Delete' }).click()
  await expect(page.getByText(/recorded activity/)).toBeVisible()
})

test('operator cannot reach the page', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', { body: me('OPERATOR') })
  await page.goto('/settings/users')
  await expect(page.getByText('You are not allowed to view this page.')).toBeVisible()
})

test('admin grants an operator access to an environment and a deployment', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', { body: me('ADMIN') })
  api.mock('GET', '/api/users', { body: users })
  api.mock('GET', '/api/settings/security', { body: { require2faForAdmins: false, updatedAt: null } })
  api.mock('GET', '/api/hosts', {
    body: [{ id: 'h1', name: 'prod', connectionType: 'LOCAL', host: null, port: null, tls: false, createdAt: '2026-10-01T10:00:00Z' }],
  })
  api.mock('GET', '/api/hosts/h1/managed-deployments', {
    body: [{ id: 'd1', name: 'web', status: 'RUNNING', source: 'FILE', lastDeployedAt: null, createdAt: '2026-10-01T10:00:00Z' }],
  })
  api.mock('GET', '/api/users/u2/permissions', { body: { grants: [] } })
  api.mock('PUT', '/api/users/u2/permissions', { body: { grants: [{ deploymentId: 'd1', deploymentHostId: 'h1', level: 'OPERATE' }] } })
  await page.goto('/settings/users')
  await expect(page.getByRole('button', { name: 'Access for u' })).toHaveCount(0)

  await page.getByRole('button', { name: 'Access for anna' }).click()
  const dialog = page.getByRole('dialog')
  await dialog.getByLabel('Access to web').selectOption('OPERATE')
  await dialog.getByRole('button', { name: 'Save access' }).click()
  await expect(dialog).toHaveCount(0)
})
