import { defineConfig, devices } from '@playwright/test'

import { resolveRunPort } from './e2e-config/port'
import { systemProbe } from './e2e-config/system-probe'

// Each run takes its own free port (see e2e-config/port.ts), so parallel runs in one checkout or in several never share
// a dev server (shipr-k31n.61). E2E_PORT overrides it.
const port = resolveRunPort(process.cwd(), process.env, 'E2E_PORT', systemProbe)

export default defineConfig({
  testDir: './e2e',
  // Pixel screenshots run on their own (`pnpm e2e:visual`, playwright.visual.config.ts), inside docker.
  testIgnore: 'visual.spec.ts',
  retries: process.env.CI ? 2 : 0,
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL: `http://localhost:${port}`,
    trace: 'on-first-retry',
    // The UI follows the browser language while the user has none set; a fixed locale keeps the UI language and Intl output identical on every machine.
    locale: 'en-US',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    command: `pnpm dev --port ${port} --strictPort`,
    url: `http://localhost:${port}`,
    reuseExistingServer: !process.env.CI,
  },
})
