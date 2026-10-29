import type { EnvEntry } from '../api.ts'
import { referencedEnvKeys, type WizardState } from '../wizard/index.ts'
import type { AppTemplate } from './api.ts'

const ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789'

/** Random alphanumeric secret from the browser's CSPRNG (rejection sampling, no modulo bias). */
export function generateSecret(length = 24): string {
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
  const hinted = template.env.map((h) => ({ key: h.key, value: h.generate ? generateSecret() : (h.defaultValue ?? '') }))
  const extra = referencedEnvKeys(state)
    .filter((k) => !hinted.some((h) => h.key === k))
    .map((key) => ({ key, value: '' }))
  return [...hinted, ...extra]
}
