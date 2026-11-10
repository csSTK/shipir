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

// Concurrent runs (shipr-k31n.61): two runs in one checkout derive the same port. With reuseExistingServer the second
// attached to the first run's dev server, and whichever finished first tore it down under the other. Probing the
// derived port does not help either: both runs see it free while the first Vite is still starting. So without an
// explicit override every run asks the OS for a free port, a few microseconds before Vite binds it, and never shares
// a server. An explicit override is the user's choice and is used as is.
export interface PortSource {
  freePort: () => number
}

// Resolves the port once per run and pins it in `envName`: Playwright workers load the config again in child
// processes that inherit the environment, and they must agree with the runner on the port.
export function resolveRunPort(
  checkoutDir: string,
  env: Record<string, string | undefined>,
  envName: string,
  source: PortSource,
): number {
  const override = env[envName]
  if (override !== undefined && override !== '') return e2ePort(checkoutDir, override)
  const port = source.freePort()
  env[envName] = String(port)
  return port
}
