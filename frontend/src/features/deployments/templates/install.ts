import type { EnvEntry } from '../api.ts'
import { referencedEnvKeys, type WizardState } from '../wizard/index.ts'
import type { AppTemplate } from './api.ts'

const ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789'

/** Random alphanumeric secret from the browser's CSPRNG (rejection sampling, no modulo bias). */
export function generateSecret(length = 32): string {
  const limit = 256 - (256 % ALPHABET.length)
  let out = ''
  while (out.length < length) {
    for (const byte of crypto.getRandomValues(new Uint8Array(length * 2))) {
      if (byte < limit && out.length < length) out += ALPHABET[byte % ALPHABET.length]
    }
  }
  return out
}

/** `base`, or `base-2`, `base-3`, ... the first name not in `taken`. */
export function suggestName(base: string, taken: string[]): string {
  if (!taken.includes(base)) return base
  let n = 2
  while (taken.includes(`${base}-${n}`)) n++
  return `${base}-${n}`
}

/** Initial variables: hinted keys first (generated, default or empty), then any other key the spec references. */
export function initialEnv(template: AppTemplate, state: WizardState): EnvEntry[] {
  const hinted = template.env.map((h) => ({ key: h.key, value: h.generate ? generateSecret(h.length ?? undefined) : (h.defaultValue ?? '') }))
  const extra = referencedEnvKeys(state)
    .filter((k) => !hinted.some((h) => h.key === k))
    .map((key) => ({ key, value: '' }))
  return [...hinted, ...extra]
}

/** The service and container port the app is reached on: the first published TCP port of the first service that has one. */
export function webTarget(template: AppTemplate): { service: string; port: number } | null {
  for (const s of template.spec.services ?? []) {
    const port = (s.ports ?? []).find((p) => p.host != null && p.protocol !== 'udp')
    if (port) return { service: s.name, port: port.container }
  }
  return null
}

/**
 * The Compose container of `service` in `project` (a deployment's project is its name): `<project>-<service>-<n>`, or the
 * legacy `<project>_<service>_<n>`. Null while the container does not exist yet.
 */
export function matchContainer(candidates: { containerName: string }[], project: string, service: string): string | null {
  const escape = (s: string) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const pattern = new RegExp(`^${escape(project)}[-_]${escape(service)}[-_]\\d+$`)
  return candidates.find((c) => pattern.test(c.containerName))?.containerName ?? null
}
