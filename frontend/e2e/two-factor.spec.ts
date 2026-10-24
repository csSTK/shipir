import { expect, test } from './fixtures/api.ts'

const admin = { username: 'admin', role: 'ADMIN', setupRequired: false, setupStep: null, twoFactorEnabled: true }

test('signs in with a second factor, and a wrong code stays on the step', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', { status: 401 })
  api.mock('POST', '/api/auth/login', { body: { mfaRequired: true } })
  api.mock('GET', '/api/hosts/overview', { body: [] })
  await page.goto('/')
  await page.getByLabel('Username').fill('admin')
  await page.getByLabel('Password').fill('correct horse')
  await page.getByRole('button', { name: 'Sign in' }).click()

  api.mock('POST', '/api/auth/2fa/verify', { status: 401, body: { code: 'INVALID_CODE' } })
  await page.getByLabel('Authentication code').fill('000000')
  await page.getByRole('button', { name: 'Verify' }).click()
  await expect(page.getByRole('alert')).toContainText('not valid')

  api.mock('POST', '/api/auth/2fa/verify', { body: admin })
  await page.getByLabel('Authentication code').fill('123456')
  await page.getByRole('button', { name: 'Verify' }).click()
  await expect(page.getByRole('link', { name: 'Shipir' })).toBeVisible()
})

test('enrols from the account page and shows the recovery codes once', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', { body: { ...admin, twoFactorEnabled: false } })
  api.mock('GET', '/api/hosts/overview', { body: [] })
  api.mock('GET', '/api/notifications/preferences', { body: [] })
  api.mock('POST', '/api/auth/2fa/setup', {
    body: { secret: 'JBSWY3DPEHPK3PXP', otpauthUri: 'otpauth://totp/Shipir:admin?secret=JBSWY3DPEHPK3PXP&issuer=Shipir' },
  })
  api.mock('POST', '/api/auth/2fa/confirm', { body: { recoveryCodes: ['AAAAA-11111', 'BBBBB-22222'] } })
  await page.goto('/account')

  await page.getByRole('button', { name: 'Enable two-factor authentication' }).click()
  await expect(page.getByRole('img', { name: /QR code/ })).toBeVisible()
  await expect(page.getByText('JBSWY3DPEHPK3PXP')).toBeVisible()
  await page.getByLabel('Verification code').fill('123456')
  await page.getByRole('button', { name: 'Confirm' }).click()
  await expect(page.getByText('AAAAA-11111')).toBeVisible()
  await expect(page.getByText('JBSWY3DPEHPK3PXP')).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Download codes' })).toBeVisible()
})

test('admin resets 2FA of another user and toggles the requirement; operator is blocked', async ({ page, api }) => {
  const base = { disabled: false, passwordChangeRequired: false, createdAt: '2026-10-01T10:00:00Z', passwordChangedAt: '2026-10-02T10:00:00Z', disabledAt: null }
  api.mock('GET', '/api/auth/me', { body: admin })
  api.mock('GET', '/api/hosts/overview', { body: [] })
  api.mock('GET', '/api/users', {
    body: [
      { ...base, id: 'u1', username: 'admin', role: 'ADMIN', twoFactorEnabled: true },
      { ...base, id: 'u2', username: 'anna', role: 'OPERATOR', twoFactorEnabled: true },
    ],
  })
  api.mock('GET', '/api/settings/security', { body: { require2faForAdmins: false, updatedAt: null } })
  api.mock('PUT', '/api/settings/security', { body: { require2faForAdmins: true, updatedAt: '2026-10-24T10:00:00Z' } })
  api.mock('POST', '/api/users/u2/reset-2fa', { status: 204 })
  await page.goto('/settings/users')

  await expect(page.getByRole('button', { name: 'Reset 2FA for admin' })).toHaveCount(0)
  await page.getByRole('switch', { name: 'Require 2FA for ADMINs' }).click()
  await expect(page.getByRole('switch', { name: 'Require 2FA for ADMINs' })).toBeChecked()

  await page.getByRole('button', { name: 'Reset 2FA for anna' }).click()
  await page.getByRole('dialog').getByRole('button', { name: 'Reset 2FA' }).click()
  await expect(page.getByRole('dialog')).toHaveCount(0)
})

test('forced setup step asks an admin to enrol before the app opens', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', { body: { ...admin, twoFactorEnabled: false, setupRequired: true, setupStep: 'TWO_FACTOR' } })
  api.mock('POST', '/api/auth/2fa/setup', {
    body: { secret: 'JBSWY3DPEHPK3PXP', otpauthUri: 'otpauth://totp/Shipir:admin?secret=JBSWY3DPEHPK3PXP' },
  })
  await page.goto('/')
  await expect(page.getByRole('heading', { name: 'Set up two-factor authentication' })).toBeVisible()
  await expect(page.getByRole('img', { name: /QR code/ })).toBeVisible()
})
