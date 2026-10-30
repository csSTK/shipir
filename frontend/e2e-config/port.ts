// Every checkout (main repo, each worktree) gets its own default e2e port derived from its directory, so
// `reuseExistingServer` can only ever attach to a dev server started from this same checkout.
const FIRST_PORT = 20_000
const PORT_COUNT = 20_000 // 20000-39999: below the OS ephemeral range, away from common dev ports

// FNV-1a, 32 bit: stable across runs and machines, no dependency.
function hash(text: string): number {
  let h = 0x811c9dc5
  for (let i = 0; i < text.length; i++) {
    h ^= text.charCodeAt(i)
    h = Math.imul(h, 0x01000193) >>> 0
  }
  return h
}

export function e2ePort(checkoutDir: string, override?: string): number {
  if (override !== undefined && override !== '') {
    const port = Number(override)
    if (!Number.isInteger(port) || port < 1 || port > 65_535) {
      throw new Error(`E2E_PORT must be a port number between 1 and 65535, got "${override}"`)
    }
    return port
  }
  return FIRST_PORT + (hash(checkoutDir) % PORT_COUNT)
}
