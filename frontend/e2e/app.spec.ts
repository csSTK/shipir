import { expect, test } from './fixtures/api.ts'

test('opens the app and shows the Shipir heading', async ({ page }) => {
  await page.goto('/')
  await expect(page.getByRole('heading', { name: 'Shipir' })).toBeVisible()
})
