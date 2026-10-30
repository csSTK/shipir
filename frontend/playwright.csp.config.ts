import { defineConfig, devices } from '@playwright/test'

import { e2ePort } from './e2e-config/port'

// Runs e2e-csp/ against the production build (the dev server needs inline scripts, so it cannot run under the
// real policy). `pnpm e2e:csp` = build + preview + this config. Separate from the main e2e run on purpose.
// Per-checkout default port (see e2e-config/port.ts), so a preview server of another checkout is never reused.
const port = e2ePort(`${process.cwd()}#csp`, process.env.E2E_CSP_PORT)

export default defineConfig({
  testDir: './e2e-csp',
  retries: process.env.CI ? 2 : 0,
  reporter: [['list']],
  use: { baseURL: `http://localhost:${port}`, locale: 'en-US' },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    command: `pnpm build && pnpm exec vite preview --port ${port} --strictPort`,
    url: `http://localhost:${port}`,
    reuseExistingServer: !process.env.CI,
    timeout: 240_000,
  },
})
