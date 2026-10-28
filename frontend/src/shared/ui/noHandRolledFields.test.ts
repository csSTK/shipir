import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join, relative } from 'node:path'
import { describe, expect, it } from 'vitest'

const SRC = join(__dirname, '..', '..')
/** Class names of the old hand-rolled field markup; fields go through FormField/TextField/Select/Switch. */
const HAND_ROLLED = /ui-form-field|wz-field|wz-check/
/** Files allowed to mention the old classes, with the reason. Keep empty unless a usage truly cannot migrate. */
const ALLOWLIST: Record<string, string> = {}

function tsxFiles(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name)
    if (statSync(path).isDirectory()) return tsxFiles(path)
    return path.endsWith('.tsx') && !path.endsWith('.test.tsx') ? [path] : []
  })
}

describe('form fields', () => {
  it('use the shared FormField/TextField/Select/Switch, not hand-rolled label markup', () => {
    const offenders = tsxFiles(SRC)
      .map((f) => relative(SRC, f))
      .filter((f) => !(f in ALLOWLIST))
      .filter((f) => HAND_ROLLED.test(readFileSync(join(SRC, f), 'utf8')))
    expect(offenders).toEqual([])
  })
})
