import { readdirSync, readFileSync } from 'node:fs'
import { dirname, join, relative } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'

// A bare `1fr` track has an implicit min-content minimum, so a long name or path widens the grid past its
// container. Every fr track must sit inside minmax(...) (usually minmax(0, 1fr)). Sheets are read from disk
// because Vitest blanks CSS. Also no 100vw (ignores scrollbars and mobile browser chrome; use dvw/%).
const srcRoot = join(dirname(fileURLToPath(import.meta.url)), '../..')

const strip = (css: string) => css.replace(/\/\*[\s\S]*?\*\//g, '')
const cssFiles = (dir: string): string[] =>
  readdirSync(dir, { withFileTypes: true }).flatMap((e) =>
    e.isDirectory() ? cssFiles(join(dir, e.name)) : e.name.endsWith('.css') ? [join(dir, e.name)] : [],
  )

const MINMAX = /minmax\((?:[^()]|\([^()]*\))*\)/g
const FR = /(?<![\w.-])\d*\.?\d+fr\b/

// Declarations end at ';', '{' or '}', so a value wrapped onto the next line is still one declaration.
const declarations = (css: string): string[] =>
  strip(css)
    .split(/[;{}]/)
    .map((d) => d.replace(/\s+/g, ' ').trim())
    .filter(Boolean)

const bareFrDeclarations = (css: string): string[] =>
  declarations(css).filter(
    (d) => /(grid-template-(columns|rows)|grid-auto-(columns|rows))\s*:/.test(d) && FR.test(d.replace(MINMAX, '')),
  )

const sheets = cssFiles(srcRoot).map((f) => ({
  path: '/' + relative(srcRoot, f).split('\\').join('/'),
  css: readFileSync(f, 'utf8'),
}))

describe('grid tracks in CSS', () => {
  it('scans the real stylesheets', () => {
    expect(sheets.length).toBeGreaterThan(50)
  })

  it('detects a bare fr track (self-check)', () => {
    expect(bareFrDeclarations('.a { grid-template-columns: 1fr 1fr; }')).toHaveLength(1)
    expect(bareFrDeclarations('.a { grid-template-columns: max-content 1.5fr; }')).toHaveLength(1)
    expect(bareFrDeclarations('.a { grid-template-columns: repeat(2, minmax(0, 1fr)); }')).toHaveLength(0)
    expect(bareFrDeclarations('.a { grid-template-columns: repeat(auto-fit, minmax(min(20rem, 100%), 1fr)); }')).toHaveLength(0)
  })

  it('detects a bare fr track whose declaration wraps onto another line (self-check)', () => {
    expect(bareFrDeclarations('.a {\n  grid-template-columns:\n    1fr 1fr;\n}')).toHaveLength(1)
    expect(bareFrDeclarations('.a {\n  grid-template-columns: repeat(2,\n    minmax(0, 1fr));\n}')).toHaveLength(0)
  })

  it('uses no bare fr track', () => {
    const offenders = sheets.flatMap((s) => bareFrDeclarations(s.css).map((l) => `${s.path}: ${l}`))
    expect(offenders).toEqual([])
  })

  it('uses no 100vw', () => {
    const offenders = sheets.filter((s) => /\b100vw\b/.test(strip(s.css))).map((s) => s.path)
    expect(offenders).toEqual([])
  })
})
