import { expect, test } from './fixtures/api.ts'

const me = { username: 'u', role: 'ADMIN', setupRequired: false }
const base = { passwordChangeRequired: false, createdAt: '2026-10-01T10:00:00Z', passwordChangedAt: null, disabledAt: null, disabled: false }
const users = [
  { ...base, id: 'u2', username: 'anna', role: 'OPERATOR' },
  { ...base, id: 'u3', username: 'bob', role: 'VIEWER' },
]
const ops = { id: 't1', name: 'Ops', memberCount: 1, createdAt: '2026-10-20T10:00:00Z' }
const opsDetail = { ...ops, members: [{ userId: 'u2', username: 'anna' }] }

test('admin creates a team, edits its members and grants access', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', { body: me })
  api.mock('GET', '/api/teams', { body: [ops] })
  api.mock('GET', '/api/users', { body: users })
  api.mock('POST', '/api/teams', { status: 201, body: { ...ops, id: 't2', name: 'Dev', memberCount: 0 } })
  api.mock('GET', '/api/teams/t1', { body: opsDetail })
  api.mock('PUT', '/api/teams/t1/members', { body: { ...opsDetail, members: [...opsDetail.members, { userId: 'u3', username: 'bob' }] } })
  api.mock('GET', '/api/hosts', {
    body: [{ id: 'h1', name: 'prod', connectionType: 'LOCAL', host: null, port: null, tls: false, createdAt: '2026-10-01T10:00:00Z' }],
  })
  api.mock('GET', '/api/hosts/h1/managed-deployments', { body: [] })
  api.mock('GET', '/api/teams/t1/permissions', { body: { grants: [] } })
  api.mock('PUT', '/api/teams/t1/permissions', { body: { grants: [{ hostId: 'h1', level: 'VIEW' }] } })

  await page.goto('/settings/teams')
  await expect(page.getByText('Ops', { exact: true })).toBeVisible()

  await page.getByRole('button', { name: 'Add team' }).click()
  await page.getByRole('dialog').getByLabel('Name').fill('Dev')
  await page.getByRole('button', { name: 'Create team' }).click()
  await expect(page.getByRole('dialog')).toHaveCount(0)

  await page.getByRole('button', { name: 'Members of Ops' }).click()
  const members = page.getByRole('dialog')
  await expect(members.getByLabel('anna')).toBeChecked()
  await members.getByLabel('bob').check()
  await members.getByRole('button', { name: 'Save members' }).click()
  await expect(members).toHaveCount(0)

  await page.getByRole('button', { name: 'Access for Ops' }).click()
  const access = page.getByRole('dialog')
  await access.getByLabel('Access to prod').selectOption('VIEW')
  await access.getByRole('button', { name: 'Save access' }).click()
  await expect(access).toHaveCount(0)
})

test('a taken team name shows an inline error', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', { body: me })
  api.mock('GET', '/api/teams', { body: [ops] })
  api.mock('POST', '/api/teams', { status: 409, body: { code: 'TEAM_NAME_TAKEN', message: 'x' } })
  await page.goto('/settings/teams')
  await page.getByRole('button', { name: 'Add team' }).click()
  await page.getByRole('dialog').getByLabel('Name').fill('Ops')
  await page.getByRole('button', { name: 'Create team' }).click()
  await expect(page.getByText('This team name is already taken.')).toBeVisible()
})

test('operator cannot reach the page', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', { body: { ...me, role: 'OPERATOR' } })
  await page.goto('/settings/teams')
  await expect(page.getByText('You are not allowed to view this page.')).toBeVisible()
})
