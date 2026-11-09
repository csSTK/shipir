import type { Locator, Page } from '@playwright/test'

import { expect, test, type ApiMock } from './fixtures/api.ts'
import { mockBroker } from './fixtures/stomp.ts'
import { mockCommon, mockContainerDetails, mockSignedOut, mockUsers } from './fixtures/visual-data.ts'

// Pixel regression tests for the main pages. Baselines (e2e/visual-baselines/) record how the UI looks today and are
// regenerated on purpose with `pnpm e2e:visual:update` after a design change. Only valid inside the Playwright image
// (`pnpm e2e:visual` runs it there).
// Per page and viewport: `<id>.png` = the viewport (shell included); `<id>-content.png` = the full-height main region,
// only when the page scrolls. Determinism: fixed clock (Date only, timers keep running), fixed locale and time
// zone (config), animations off, reduced motion, caret hidden, fonts awaited, mocked API and websocket.

// 2026-11-09 12:00 UTC: after every date in the fixture, so relative times ("2 hours ago") never change.
const NOW = new Date('2026-11-09T12:00:00Z')

interface Target {
  name: string
  path: string
  /** A page-specific element that exists only once the page shows its data (not "Loading"). */
  ready: (page: Page) => Locator
  setup?: (api: ApiMock) => void
  /** Steps after load that open an overlay; they end with the overlay visible. */
  open?: (page: Page) => Promise<void>
  /** Also capture in dark mode. */
  dark?: boolean
}

const targets: Target[] = [
  { name: 'overview', path: '/', ready: (p) => p.getByText('Local').first(), dark: true },
  { name: 'dashboard', path: '/environments/a', ready: (p) => p.getByRole('heading', { name: 'Local', exact: true }) },
  { name: 'containers', path: '/environments/a/containers', ready: (p) => p.getByText('web-with-a-rather-long-container-name').first(), dark: true },
  {
    name: 'container-detail',
    path: '/environments/a/containers/1',
    ready: (p) => p.getByRole('heading', { name: 'web-with-a-rather-long-container-name' }),
    setup: mockContainerDetails,
  },
  { name: 'deployments', path: '/environments/a/deployments', ready: (p) => p.getByText('shop').first() },
  {
    name: 'deployment-detail',
    path: '/environments/a/deployments/s1',
    ready: (p) => p.getByRole('heading', { name: 'shop' }),
    dark: true,
  },
  {
    name: 'compose-wizard',
    path: '/environments/a/deployments',
    ready: (p) => p.getByText('shop').first(),
    open: async (page) => {
      await page.getByRole('button', { name: 'Add deployment' }).click()
      await page.getByRole('radio', { name: 'Form wizard' }).check()
      await expect(page.getByLabel('Service 1 name')).toBeVisible()
    },
  },
  { name: 'images', path: '/environments/a/images', ready: (p) => p.getByText('nginx:1').first() },
  { name: 'networks', path: '/environments/a/networks', ready: (p) => p.getByText('172.17.0.0/16') },
  { name: 'volumes', path: '/environments/a/volumes', ready: (p) => p.getByText('pgdata').first() },
  { name: 'account', path: '/account', ready: (p) => p.getByRole('main').getByRole('heading').first() },
  { name: 'settings-ai', path: '/settings/ai', ready: (p) => p.getByLabel('Model') },
  { name: 'settings-users', path: '/settings/users', ready: (p) => p.getByText('anna', { exact: true }), setup: mockUsers },
  { name: 'settings-retention', path: '/settings/retention', ready: (p) => p.getByLabel('Audit log retention (days)') },
  { name: 'settings-notifications', path: '/settings/notifications', ready: (p) => p.getByText('Ops chat with a rather long webhook name') },
  { name: 'notifications', path: '/notifications', ready: (p) => p.getByText('Environment "Local" is down.').first() },
  {
    name: 'assistant-dock',
    path: '/',
    ready: (p) => p.getByText('Local').first(),
    open: async (page) => {
      await page.getByRole('button', { name: 'Open assistant' }).click()
      const panel = page.getByRole('dialog', { name: 'Assistant' })
      await panel.getByRole('combobox', { name: 'Chat' }).selectOption('s1')
      await expect(panel.getByText('Two deployments run', { exact: false })).toBeVisible()
    },
  },
  { name: 'login', path: '/', ready: (p) => p.getByLabel('Username'), setup: mockSignedOut, dark: true },
]

const viewports = [
  { name: '375', width: 375, height: 800 },
  { name: '1440', width: 1440, height: 900 },
]

test.beforeEach(async ({ page, api }) => {
  mockCommon(api)
  // Keeps the websocket quiet and identical on every run; no frames are pushed.
  await mockBroker(page, () => {})
  await page.clock.setFixedTime(NOW)
})

for (const target of targets) {
  for (const viewport of viewports) {
    for (const scheme of target.dark ? (['light', 'dark'] as const) : (['light'] as const)) {
      const id = `${target.name}-${viewport.name}-${scheme}`
      test(id, async ({ page, api }) => {
        await page.emulateMedia({ colorScheme: scheme })
        await page.setViewportSize({ width: viewport.width, height: viewport.height })
        target.setup?.(api)
        await page.goto(target.path)
        // A client-side redirect or re-render after load must not race the capture (see shipr-k31n.55).
        await page.waitForURL((url) => url.pathname === target.path)
        await expect(target.ready(page)).toBeVisible()
        await expect(page.getByText('Loading')).toHaveCount(0)
        await target.open?.(page)
        await page.evaluate(() => document.fonts.ready)
        // What a user sees: the viewport, shell included (sticky sidebar, assistant button).
        // soft: a failing viewport capture must not hide the content capture's result.
        await expect.soft(page).toHaveScreenshot(`${id}.png`)
        // Content taller than the viewport: also capture the whole main region, so the lower part is covered.
        // Overlays (dialog, dock) are fixed to the viewport and have no extra content.
        const taller = await page.evaluate(() => document.documentElement.scrollHeight > window.innerHeight)
        if (taller && !target.open) {
          // In a tall element capture the fixed assistant button would land mid-content and the sticky top bar would
          // cover the top of main, so neither takes part.
          await page.addStyleTag({
            content: '.assistant-dock__icon { visibility: hidden !important; } .shell__bar { position: static !important; }',
          })
          await expect.soft(page.getByRole('main')).toHaveScreenshot(`${id}-content.png`)
        }
      })
    }
  }
}
