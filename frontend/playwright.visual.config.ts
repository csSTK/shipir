import { defineConfig, devices } from '@playwright/test'

import { resolveRunPort } from './e2e-config/port'
import { systemProbe } from './e2e-config/system-probe'

// Pixel screenshot regression tests (e2e/visual.spec.ts), kept out of the default `pnpm e2e` run.
// Run them with `pnpm e2e:visual` (docker, see e2e/visual-docker.sh): baselines are only valid when rendered inside the
// official Playwright image, because fonts and anti-aliasing differ between macOS and Linux.
const port = resolveRunPort(`${process.cwd()}#visual`, process.env, 'E2E_PORT', systemProbe)

export default defineConfig({
  testDir: './e2e',
  testMatch: 'visual.spec.ts',
  // No retries: a retry would hide a flaky screenshot instead of exposing it.
  globalSetup: './e2e-config/warm-up.ts',
  retries: 0,
  reporter: [['list'], ['html', { open: 'never', outputFolder: 'playwright-report-visual' }]],
  // One folder of baselines named after the test, no platform suffix: there is exactly one valid platform (the image).
  snapshotPathTemplate: '{testDir}/visual-baselines/{arg}{ext}',
  expect: {
    toHaveScreenshot: {
      animations: 'disabled',
      caret: 'hide',
      // Same image, same pixels: the tolerance only absorbs sub-pixel text anti-aliasing noise (at most 0.2% of the
      // pixels may differ). A changed token moves far more than that; the tests stay red for it.
      maxDiffPixelRatio: 0.002,
    },
  },
  use: {
    baseURL: `http://localhost:${port}`,
    locale: 'en-US',
    timezoneId: 'UTC',
    // The app skips its springs and slides under reduced motion, so overlays are at rest when captured.
    reducedMotion: 'reduce',
    deviceScaleFactor: 1,
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'], deviceScaleFactor: 1 } }],
  webServer: {
    command: `pnpm dev --port ${port} --strictPort`,
    url: `http://localhost:${port}`,
    reuseExistingServer: !process.env.CI,
  },
})
