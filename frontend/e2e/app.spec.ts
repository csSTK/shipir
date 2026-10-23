import { expect, test } from './fixtures/api.ts'

test('opens the app and shows the Shipir heading when signed in', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', { body: { username: 'admin', role: 'ADMIN', setupRequired: false } })
  await page.goto('/')
  await expect(page.getByRole('heading', { name: 'Shipir' })).toBeVisible()
})
