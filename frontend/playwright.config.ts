import { defineConfig, devices } from '@playwright/test'

import { e2ePort } from './e2e-config/port'

// Default port is derived from this checkout's directory (see e2e-config/port.ts), so a Vite server of another
// checkout or worktree on 5173 can never be reused. E2E_PORT overrides it.
const port = e2ePort(process.cwd(), process.env.E2E_PORT)

export default defineConfig({
  testDir: './e2e',
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
