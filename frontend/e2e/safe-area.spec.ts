import { expect, test } from './fixtures/api.ts'

// Chromium cannot emulate env(safe-area-inset-*), so the --safe-* tokens are overridden to what an iPhone in
// landscape reports (notch left, home indicator bottom) and the resulting layout is measured.
const INSETS = ':root { --safe-left: 44px; --safe-right: 44px; --safe-bottom: 34px; }'

test.use({ viewport: { width: 390, height: 700 } })

test('phone drawer and content keep clear of the notch and the home indicator', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', { body: { username: 'admin', role: 'ADMIN', setupRequired: false } })
  api.mock('GET', '/api/hosts/overview', { body: [] })
  api.mock('GET', '/api/notifications/unread-count', { body: { count: 0 } })
  api.mock('GET', '/api/ai/sessions/pending-count', { body: { count: 0 } })
  await page.goto('/')
  await page.addStyleTag({ content: INSETS })

  const bar = page.locator('.shell__bar')
  await expect(bar).toHaveCSS('padding-left', '56px')
  await expect(bar).toHaveCSS('padding-right', '56px')
  await expect(page.locator('.shell__content')).toHaveCSS('padding-bottom', '50px')
  await expect(page.locator('.shell__content')).toHaveCSS('padding-left', '60px')

  await page.getByRole('button', { name: /navigation|menu/i }).click()
  const drawer = page.locator('.shell__drawer')
  await expect(drawer).toHaveCSS('padding-left', '44px')
  await expect(drawer).toHaveCSS('padding-bottom', '34px')
})
