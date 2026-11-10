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
  // --space-4 (16px) + home indicator (34px) + the floating assistant button's clearance (--space-7 + --space-5 = 72px).
  await expect(page.locator('.shell__content')).toHaveCSS('padding-bottom', '122px')
  await expect(page.locator('.shell__content')).toHaveCSS('padding-left', '60px')

  await page.getByRole('button', { name: /navigation|menu/i }).click()
  const drawer = page.locator('.shell__drawer')
  await expect(drawer).toHaveCSS('padding-left', '44px')
  await expect(drawer).toHaveCSS('padding-bottom', '34px')
})

test('landscape: the assistant icon stays clear of the right notch', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', { body: { username: 'admin', role: 'ADMIN', setupRequired: false } })
  api.mock('GET', '/api/hosts/overview', { body: [] })
  api.mock('GET', '/api/notifications/unread-count', { body: { count: 0 } })
  api.mock('GET', '/api/ai/sessions/pending-count', { body: { count: 0 } })
  await page.setViewportSize({ width: 844, height: 390 })
  await page.goto('/')
  await page.addStyleTag({ content: INSETS })

  // The icon sits --space-5 (1.5rem = 24px) in from the safe area on the right.
  const icon = await page.getByRole('button', { name: 'Open assistant' }).boundingBox()
  expect(icon).not.toBeNull()
  expect(844 - ((icon?.x ?? 0) + (icon?.width ?? 0))).toBe(44 + 24)
})

test('phone: the assistant sheet keeps its text clear of the notch', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', { body: { username: 'admin', role: 'ADMIN', setupRequired: false } })
  api.mock('GET', '/api/hosts/overview', { body: [] })
  api.mock('GET', '/api/notifications/unread-count', { body: { count: 0 } })
  api.mock('GET', '/api/ai/sessions/pending-count', { body: { count: 0 } })
  api.mock('GET', '/api/ai/sessions', { body: { items: [] } })
  await page.goto('/')
  await page.addStyleTag({ content: INSETS })

  await page.getByRole('button', { name: 'Open assistant' }).click()
  const sheet = page.locator('.assistant-dock__panel--sheet')
  await expect(sheet).toBeVisible()
  // --space-4 (16px) plus the inset on each side.
  await expect(sheet).toHaveCSS('padding-left', '60px')
  await expect(sheet).toHaveCSS('padding-right', '60px')
})

test('phone: without the assistant button the content only clears the home indicator', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', { body: { username: 'admin', role: 'ADMIN', setupRequired: false } })
  api.mock('GET', '/api/ai/sessions', { body: { items: [] } })
  await page.goto('/assistant')
  await page.addStyleTag({ content: INSETS })

  await expect(page.locator('.shell__content')).not.toHaveAttribute('data-dock', 'true')
  // --space-4 (16px) + home indicator (34px).
  await expect(page.locator('.shell__content')).toHaveCSS('padding-bottom', '50px')
})
