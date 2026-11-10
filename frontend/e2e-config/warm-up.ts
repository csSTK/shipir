import { chromium, type FullConfig } from '@playwright/test'

// Cold Vite (shipr-k31n.60): the first page load transforms the whole module graph and optimizes dependencies, and
// Vite may reload the page when it finds new ones. Under CPU load that exceeded the 30s test timeout of whichever test
// ran first, or destroyed its execution context. So the run loads the app once in a real browser before the first test.
// Only the module graph matters; /api is not mocked, failed calls are irrelevant here.
export default async function warmUp(config: FullConfig): Promise<void> {
  const baseURL = config.projects[0]?.use.baseURL
  if (!baseURL) return
  const browser = await chromium.launch()
  try {
    const page = await browser.newPage()
    await page.goto(baseURL, { waitUntil: 'load', timeout: 180_000 })
    // A dependency re-optimization reloads the page once; wait for the network to settle after it.
    await page.waitForLoadState('networkidle', { timeout: 60_000 }).catch(() => {})
  } finally {
    await browser.close()
  }
}
