import { readdirSync, readFileSync } from 'node:fs'
import { join, relative } from 'node:path'
import { describe, expect, it } from 'vitest'
import { findLiteralMessages, findLiteralStrings } from './literalStrings.ts'

const SRC = join(import.meta.dirname, '..', '..')

function sourceFiles(dir: string, extensions: string[]): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const path = join(dir, entry.name)
    if (entry.isDirectory()) return entry.name === 'i18n' ? [] : sourceFiles(path, extensions)
    const isTest = /\.test\.tsx?$/.test(entry.name)
    return !isTest && extensions.some((e) => entry.name.endsWith(e)) ? [path] : []
  })
}

describe('findLiteralStrings', () => {
  const find = (source: string) => findLiteralStrings('x.tsx', source)

  it('flags literal JSX text', () => {
    expect(find('const A = () => <button>Save</button>')).toEqual([expect.objectContaining({ where: 'text', text: 'Save' })])
  })

  it('flags user-facing attributes', () => {
    const source = 'const A = () => <input aria-label="Name" title="Hint" placeholder="Your name" />'
    expect(find(source).map((f) => f.where)).toEqual(['attribute', 'attribute', 'attribute'])
  })

  it('flags string literals in JSX expressions and conditional branches', () => {
    expect(find("const A = () => <p>{ok ? 'Done' : 'Failed'}</p>")).toHaveLength(2)
    expect(find('const A = () => <p>{`Hello ${name}`}</p>')).toHaveLength(1)
  })

  it('flags a user-facing attribute built from a template or conditional', () => {
    expect(find('const A = () => <i aria-label={`Open ${x}`} title={a ? "On" : "Off"} />')).toHaveLength(3)
  })

  it('ignores translated text, symbols, technical attributes and technical example values', () => {
    const source = `const A = () => (
      <div className="card" role="group" data-testid="x" placeholder="nginx:1.27">
        {t('a.b')} <span aria-hidden="true">·</span> {count} &mdash; {'/'}
      </div>
    )`
    expect(find(source)).toEqual([])
  })
})

describe('findLiteralMessages', () => {
  const find = (source: string) => findLiteralMessages('x.ts', source)

  it('flags error messages and labels in maps', () => {
    expect(find("const M = { A: 'Could not save.' }; function f() { return 'Try again.' }")).toHaveLength(2)
    expect(find('const f = (n: string) => `Delete ${n}`')).toHaveLength(1)
  })

  it('ignores translation calls, comparisons, identifiers and header names', () => {
    const source = "x = t('A.b'); y = i18n.t('ns:A.b'); if (k === 'Deploy') {} const o = { kind: 'Deploy', 'Content-Type': 'x' }; h.set('Accept', 'y')"
    expect(find(source)).toEqual([])
  })
})

describe('user-facing strings in the UI', () => {
  it('has no literal text in any component (everything goes through i18next)', () => {
    const violations = sourceFiles(SRC, ['.tsx']).flatMap((file) =>
      findLiteralStrings(file, readFileSync(file, 'utf8')).map((f) => `${relative(SRC, file)}:${f.line} (${f.where}) "${f.text}"`),
    )
    expect(violations).toEqual([])
  })

  it('has no message-like literals outside JSX (errors, headers, labels in maps)', () => {
    const violations = sourceFiles(SRC, ['.ts', '.tsx']).flatMap((file) =>
      findLiteralMessages(file, readFileSync(file, 'utf8')).map((f) => `${relative(SRC, file)}:${f.line} "${f.text}"`),
    )
    expect(violations).toEqual([])
  })

  it('uses the shared escaping Trans, never react-i18next Trans directly', () => {
    const offenders = sourceFiles(SRC, ['.tsx']).filter((file) => /import \{[^}]*\bTrans\b[^}]*\} from 'react-i18next'/.test(readFileSync(file, 'utf8')))
    expect(offenders.map((f) => relative(SRC, f))).toEqual([])
  })
})
