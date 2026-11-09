import { spawnSync } from 'node:child_process'

import { defineConfig, devices } from '@playwright/test'

import { e2ePort, ownerFromStatus, ownershipProbePath } from './e2e-config/port'

// Default port is derived from this checkout's directory (see e2e-config/port.ts), so a Vite server of another
// checkout or worktree on 5173 can never be reused. E2E_PORT overrides it.
const port = e2ePort(process.cwd(), process.env.E2E_PORT)

// Ownership marker (shipr-k31n.36): the derived port can still collide with another checkout's. Ask the server on the
// port for this checkout's index.html through Vite's /@fs/ route; a server of another checkout refuses it, so the run
// stops with a clear message instead of testing the other checkout's code. curl runs with an argument list, no shell.
const probe = spawnSync('curl', ['-s', '-o', '/dev/null', '-w', '%{http_code}', '--max-time', '2', `http://localhost:${port}${ownershipProbePath(process.cwd())}`], {
  encoding: 'utf8',
})
if (probe.error) throw probe.error
if (ownerFromStatus(Number(probe.stdout)) === 'other') {
  throw new Error(`Port ${port} is served by another checkout's dev server. Set E2E_PORT to a free port and rerun.`)
}

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
