import { defineConfig, devices } from '@playwright/test'

import { resolveRunPort } from './e2e-config/port'
import { systemProbe } from './e2e-config/system-probe'

// Runs e2e-csp/ against the production build (the dev server needs inline scripts, so it cannot run under the
// real policy). `pnpm e2e:csp` = build + preview + this config. Separate from the main e2e run on purpose.
// Each run takes its own free port (see e2e-config/port.ts).
const port = resolveRunPort(`${process.cwd()}#csp`, process.env, 'E2E_CSP_PORT', systemProbe)

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
