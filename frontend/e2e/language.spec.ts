import { expect, test } from './fixtures/api.ts'

const user = { username: 'pat', role: 'OPERATOR', setupRequired: false }

function mockAccount(api: { mock: (method: 'GET' | 'PUT', path: string, response?: { status?: number; body?: unknown }) => void }, me: object) {
  api.mock('GET', '/api/auth/me', { body: me })
  api.mock('GET', '/api/hosts/overview', { body: [] })
  api.mock('GET', '/api/account/tokens', { body: [] })
  api.mock('GET', '/api/notifications/preferences', { body: [{ type: 'HOST_DOWN', inAppEnabled: true }] })
  api.mock('PUT', '/api/auth/me/locale', { status: 200 })
}

test('switches to German in the account page and sees the translated shell', async ({ page, api }) => {
  mockAccount(api, { ...user, locale: null })
  await page.goto('/account')
  await expect(page.getByRole('link', { name: 'Environments' })).toBeVisible()

  const saved = page.waitForRequest((r) => r.method() === 'PUT' && r.url().endsWith('/api/auth/me/locale'))
  await page.getByLabel('Language').selectOption('de')
  expect((await saved).postDataJSON()).toEqual({ locale: 'de' })

  await expect(page.getByRole('link', { name: 'Umgebungen' })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Passwort ändern' })).toBeVisible()
  await expect(page.locator('html')).toHaveAttribute('lang', 'de')
  // The select keeps the focus although the tree was remounted.
  await expect(page.getByLabel('Sprache')).toBeFocused()
})

test('shows the shell in the language stored on the user', async ({ page, api }) => {
  mockAccount(api, { ...user, locale: 'de' })
  await page.goto('/')
  await expect(page.getByRole('link', { name: 'Umgebungen' })).toBeVisible()
})

test.describe('browser language German', () => {
  test.use({ locale: 'de-DE' })

  test('follows the browser while the user has chosen no language', async ({ page, api }) => {
    mockAccount(api, { ...user, locale: null })
    await page.goto('/')
    await expect(page.getByRole('link', { name: 'Umgebungen' })).toBeVisible()
  })
})
