import { expect, test } from './fixtures/api.ts'

const settings = {
  issuerUrl: 'https://id.example.com',
  clientId: 'shipir',
  clientSecretConfigured: true,
  clientSecretHint: '…ab12',
  scopes: 'openid profile email',
  groupsClaim: 'groups',
  roleMapping: { ADMIN: ['admins'], OPERATOR: [], VIEWER: [] },
  autoProvision: false,
  localLoginEnabled: true,
  enabled: true,
  redirectUri: 'https://shipir.example.com/api/auth/oidc/callback',
}
const user = (role: string) => ({ username: 'u', role, setupRequired: false, setupStep: null })

test('admin saves the settings and tests the connection', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', { body: user('ADMIN') })
  api.mock('GET', '/api/settings/oidc', { body: settings })
  api.mock('PUT', '/api/settings/oidc', { body: { ...settings, clientSecretHint: '…new1' } })
  api.mock('POST', '/api/settings/oidc/test', { body: { status: 'INVALID' } })
  await page.goto('/settings/oidc')

  await expect(page.getByLabel('Issuer URL')).toHaveValue('https://id.example.com')
  await expect(page.getByLabel('Client secret')).toHaveValue('')
  await expect(page.getByText('https://shipir.example.com/api/auth/oidc/callback')).toBeVisible()
  await page.getByLabel('Client secret').fill('super-secret')
  await page.getByRole('button', { name: 'Save' }).click()
  await expect(page.getByText('Single sign-on settings saved.')).toBeVisible()
  await expect(page.getByText('super-secret')).toHaveCount(0)
  await expect(page.getByText(/new1/)).toBeVisible()

  await page.getByRole('button', { name: 'Test connection' }).click()
  await expect(page.getByText(/does not match the issuer/)).toBeVisible()
})

test('LOCAL_LOGIN_REQUIRED is shown inline', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', { body: user('ADMIN') })
  api.mock('GET', '/api/settings/oidc', { body: settings })
  api.mock('PUT', '/api/settings/oidc', { status: 400, body: { code: 'LOCAL_LOGIN_REQUIRED', message: 'x' } })
  await page.goto('/settings/oidc')
  await page.getByRole('button', { name: 'Save' }).click()
  await expect(page.getByRole('alert')).toContainText('Local login can only be switched off')
})

test('operator is not allowed', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', { body: user('OPERATOR') })
  await page.goto('/settings/oidc')
  await expect(page.getByLabel('Issuer URL')).toHaveCount(0)
})

test('login page offers the provider and explains a refused sign-in', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', { status: 401 })
  api.mock('GET', '/api/auth/oidc/config', {
    body: { enabled: true, displayName: 'id.example.com', localLoginEnabled: false },
  })
  await page.goto('/?oidc_error=USER_NOT_PROVISIONED')
  await expect(page.getByText(/No Shipir account is linked/)).toBeVisible()
  await expect(page.getByRole('button', { name: 'Sign in with id.example.com' })).toBeVisible()
  await expect(page.getByLabel('Username')).toHaveCount(0)
  await page.getByRole('button', { name: 'Use local login' }).click()
  await expect(page.getByLabel('Username')).toBeVisible()
})

test('account shows the linked single sign-on identity', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', { body: { ...user('OPERATOR'), externalIssuer: 'https://id.example.com' } })
  api.mock('GET', '/api/account/tokens', { body: [] })
  api.mock('GET', '/api/notifications/preferences', { body: [] })
  await page.goto('/account')
  await expect(page.getByText('Linked to https://id.example.com')).toBeVisible()
})
