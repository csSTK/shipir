import { expect, test } from './fixtures/api.ts'
import { mockCommon } from './fixtures/visual-data.ts'

// Smoke check, no pixel baselines: each main page renders in light and dark at phone, laptop and wide
// viewports without a page-level horizontal scrollbar. Screenshots are attached for manual review.

const pages: { name: string; path: string; ready?: string }[] = [
  { name: 'overview', path: '/' },
  // `ready`: accessible name of the page's h1 once its data has rendered (the dashboard shows a skeleton until then).
  { name: 'dashboard', path: '/environments/a', ready: 'Local' },
  { name: 'containers', path: '/environments/a/containers' },
  { name: 'deployments', path: '/environments/a/deployments' },
  { name: 'images', path: '/environments/a/images' },
  { name: 'networks', path: '/environments/a/networks' },
  { name: 'volumes', path: '/environments/a/volumes' },
  { name: 'account', path: '/account' },
  { name: 'notifications', path: '/notifications' },
  { name: 'deployment-detail', path: '/environments/a/deployments/s1' },
  { name: 'assistant', path: '/assistant/s1' },
  { name: 'ai-settings', path: '/settings/ai' },
  { name: 'retention-settings', path: '/settings/retention' },
  { name: 'webhook-settings', path: '/settings/notifications' },
]
const viewports = [
  { name: '375', width: 375, height: 800 },
  { name: '1280', width: 1280, height: 800 },
  { name: '1920', width: 1920, height: 1080 },
]
const schemes = ['light', 'dark'] as const

test.beforeEach(({ api }) => {
  mockCommon(api)
})

for (const scheme of schemes) {
  for (const viewport of viewports) {
    for (const target of pages) {
      test(`${target.name} at ${viewport.name}px, ${scheme}: no horizontal scroll`, async ({ page }, testInfo) => {
        await page.emulateMedia({ colorScheme: scheme })
        await page.setViewportSize({ width: viewport.width, height: viewport.height })
        await page.goto(target.path)
        // A client-side redirect or a re-render after `load` would destroy the execution context of the check below:
        // wait for the final URL, then for the page's data to be on screen before measuring.
        await page.waitForURL((url) => url.pathname === target.path)
        if (target.ready) await expect(page.getByRole('heading', { name: target.ready, exact: true })).toBeVisible()
        // The assistant page nests its own <main> inside the shell's, so take the outer (shell) one.
        await expect(page.getByRole('main').first()).toBeVisible()
        await expect(page.getByText('Loading')).toHaveCount(0)
        const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)
        expect(overflow, 'page-level horizontal overflow in px').toBeLessThanOrEqual(0)
        await testInfo.attach(`${target.name}-${viewport.name}-${scheme}`, {
          body: await page.screenshot(),
          contentType: 'image/png',
        })
      })
    }
  }
}
