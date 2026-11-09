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

// Ownership marker (shipr-k31n.36): two checkouts can still derive the same port (about 1 in 20000). Vite refuses to
// serve files outside its workspace root (server.fs.strict), so this request is answered 200 only by a dev server
// started from this checkout, 403 by a server of another checkout, and not answered (status 0) on a free port.
export type PortOwner = 'free' | 'this-checkout' | 'other'

export function ownershipProbePath(checkoutDir: string): string {
  return `/@fs${encodeURI(checkoutDir)}/index.html`
}

export function ownerFromStatus(status: number): PortOwner {
  if (status === 0) return 'free'
  return status === 200 ? 'this-checkout' : 'other'
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
