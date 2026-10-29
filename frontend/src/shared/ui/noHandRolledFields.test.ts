import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join, relative } from 'node:path'
import { describe, expect, it } from 'vitest'

const SRC = join(__dirname, '..', '..')
/** Class names of the old hand-rolled field markup; fields go through FormField/TextField/Select/Switch. */
const HAND_ROLLED = /ui-form-field|wz-field|wz-check|env-form-field|env-form__field/
/** Files allowed to mention the old classes, with the reason. Keep empty unless a usage truly cannot migrate. */
/** `<input ... type="checkbox"`, tolerating `=>` inside attributes before the type. */
const BARE_CHECKBOX = /<input\b(?:[^>]|=>)*?type=["']checkbox["']/s
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

  it('use the shared Checkbox or Switch, not a bare <input type="checkbox">', () => {
    const offenders = tsxFiles(SRC)
      .map((f) => relative(SRC, f))
      .filter((f) => !f.startsWith(join('shared', 'ui') + '/'))
      .filter((f) => !(f in ALLOWLIST))
      .filter((f) => BARE_CHECKBOX.test(readFileSync(join(SRC, f), 'utf8')))
    expect(offenders).toEqual([])
  })

  it('checkbox pattern matches bare checkboxes and ignores other inputs', () => {
    expect(BARE_CHECKBOX.test('<input type="checkbox" checked />')).toBe(true)
    expect(BARE_CHECKBOX.test('<input onChange={(e) => x(e)} type="checkbox" />')).toBe(true)
    expect(BARE_CHECKBOX.test("<input\n  name={n}\n  type='checkbox'\n/>")).toBe(true)
    expect(BARE_CHECKBOX.test('<input type="text" />')).toBe(false)
    expect(BARE_CHECKBOX.test('<input type="text" onChange={(e) => x(e)} /><p type="checkbox" />')).toBe(false)
    expect(BARE_CHECKBOX.test('<Checkbox label="x" />')).toBe(false)
  })
})
