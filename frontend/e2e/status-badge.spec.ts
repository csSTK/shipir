import { expect, test } from './fixtures/api.ts'

// The status badge of a table row must sit fully inside its cell: the table cell clips overflow, so a badge wider
// than its column shows a cut-off right edge. Checked with layout boxes, not pixels, at a phone and a laptop width.

const VIEWPORTS = [
  { width: 375, height: 800 },
  { width: 1440, height: 900 },
]

const base = {
  passwordChangeRequired: false,
  createdAt: '2026-10-01T10:00:00Z',
  passwordChangedAt: '2026-10-03T10:00:00Z',
  disabledAt: '2026-10-04T10:00:00Z',
}
const users = [
  { ...base, id: 'u1', username: 'u', role: 'ADMIN', disabled: false, disabledAt: null },
  { ...base, id: 'u2', username: 'anna', role: 'OPERATOR', disabled: true },
]
const hook = {
  id: 'w1',
  name: 'Ops chat',
  urlHint: 'https://hooks.example.com/…x9f2',
  eventTypes: ['HOST_DOWN'],
  enabled: false,
  lastDeliveryAt: null,
  lastDeliveryStatus: null,
}
const me = { username: 'u', role: 'ADMIN', setupRequired: false }

/** Returns how far the badge's box sticks out of its table cell (0 when it fits). */
async function overflowOfCell(badge: import('@playwright/test').Locator): Promise<number> {
  return badge.evaluate((el) => {
    const cell = el.closest('.rtable__cell')
    if (!cell) throw new Error('badge is not inside a table cell')
    const b = el.getBoundingClientRect()
    const c = cell.getBoundingClientRect()
    return Math.max(0, b.right - c.right, c.left - b.left)
  })
}

for (const viewport of VIEWPORTS) {
  test.describe(`status badge at ${viewport.width}px`, () => {
    test.use({ viewport })

    test('disabled user badge fits its cell on the users page', async ({ page, api }) => {
      api.mock('GET', '/api/auth/me', { body: me })
      api.mock('GET', '/api/users', { body: users })
      api.mock('GET', '/api/settings/security', { body: { require2faForAdmins: false, updatedAt: null } })
      await page.goto('/settings/users')

      const badge = page.getByText('Disabled', { exact: true })
      await expect(badge).toBeVisible()
      expect(await overflowOfCell(badge)).toBe(0)
    })

    test('disabled webhook badge fits its cell on the notifications page', async ({ page, api }) => {
      api.mock('GET', '/api/auth/me', { body: me })
      api.mock('GET', '/api/notifications/webhooks', { body: [hook] })
      await page.goto('/settings/notifications')

      const badge = page.getByText('Disabled', { exact: true })
      await expect(badge).toBeVisible()
      expect(await overflowOfCell(badge)).toBe(0)
    })
  })
}

// German labels are longer ("Deaktiviert"), so the German UI needs the same room.
test.describe('status badge in German', () => {
  test.use({ viewport: { width: 1440, height: 900 }, locale: 'de-DE' })

  test('disabled user badge fits its cell on the users page', async ({ page, api }) => {
    api.mock('GET', '/api/auth/me', { body: me })
    api.mock('GET', '/api/users', { body: users })
    api.mock('GET', '/api/settings/security', { body: { require2faForAdmins: false, updatedAt: null } })
    await page.goto('/settings/users')

    const badge = page.getByText('Deaktiviert', { exact: true }).first()
    await expect(badge).toBeVisible()
    expect(await overflowOfCell(badge)).toBe(0)
  })

  test('disabled webhook badge fits its cell on the notifications page', async ({ page, api }) => {
    api.mock('GET', '/api/auth/me', { body: me })
    api.mock('GET', '/api/notifications/webhooks', { body: [hook] })
    await page.goto('/settings/notifications')

    const badge = page.getByText('Deaktiviert', { exact: true })
    await expect(badge).toBeVisible()
    expect(await overflowOfCell(badge)).toBe(0)
  })
})
