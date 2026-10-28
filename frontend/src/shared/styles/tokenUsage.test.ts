import { readdirSync, readFileSync } from 'node:fs'
import { dirname, join, relative } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'

// Feature and shared/ui CSS may only use design tokens: no hex colors, no raw font sizes.
// Vitest blanks CSS (even ?raw via import.meta.glob), so every sheet is read from disk.
const srcRoot = join(dirname(fileURLToPath(import.meta.url)), '../..')
const MIN_SHEETS = 50

const strip = (css: string) => css.replace(/\/\*[\s\S]*?\*\//g, '')

const cssFiles = (dir: string): string[] =>
  readdirSync(dir, { withFileTypes: true }).flatMap((e) =>
    e.isDirectory() ? cssFiles(join(dir, e.name)) : e.name.endsWith('.css') ? [join(dir, e.name)] : [],
  )

type Rule = 'hex-color' | 'raw-font-size'

// Each scanner returns the offending lines (trimmed) of a stylesheet, comments ignored.
const scanners: Record<Rule, (css: string) => string[]> = {
  'hex-color': (css) => strip(css).split('\n').filter((l) => /#[0-9a-fA-F]{3,8}\b/.test(l)).map((l) => l.trim()),
  // Capture the value first: a lookahead after `\s*` would backtrack and wrongly flag `var(--text-*)`.
  // Also covers `font:` shorthand (anything but `inherit` sets a raw size), whitespace before the colon and any case.
  'raw-font-size': (css) =>
    strip(css)
      .split('\n')
      .filter((l) => {
        const size = [...l.matchAll(/font-size\s*:\s*([^;}]*)/gi)].some(
          ([, v]) => !/^(var\(--text-|inherit\b|1em(?![\w.]))/i.test(v.trim()),
        )
        const shorthand = [...l.matchAll(/(?<![-\w])font\s*:\s*([^;}]*)/gi)].some(([, v]) => !/^inherit\b/i.test(v.trim()))
        return size || shorthand
      })
      .map((l) => l.trim()),
}

type Sheet = { path: string; css: string }

const allSheets: Sheet[] = cssFiles(srcRoot).map((f) => ({
  path: '/' + relative(srcRoot, f).split('\\').join('/'),
  css: readFileSync(f, 'utf8'),
}))
const checked = allSheets.filter((s) => !s.path.startsWith('/shared/styles/'))

// Existing violations, recorded so the scans stay green until shipr-ycvc.32 migrates them.
// shipr-ycvc.32 removes these entries; a stale entry (count no longer matches) fails the test,
// so the list can only shrink. Key: `<rule> <file>` -> number of offending lines.
const allowlist: Record<string, number> = {}

const countsFor = (rule: Rule): Record<string, number> =>
  Object.fromEntries(
    checked.flatMap((s) => {
      const n = scanners[rule](s.css).length
      return n > 0 ? [[`${rule} ${s.path}`, n] as const] : []
    }),
  )

describe('design token usage', () => {
  it('scans enough non-empty stylesheets (never vacuous)', () => {
    expect(allSheets.length).toBeGreaterThan(MIN_SHEETS)
    expect(checked.length).toBeGreaterThan(MIN_SHEETS - 10)
    const empty = allSheets.filter((s) => s.css.trim() === '').map((s) => s.path)
    expect(empty).toEqual([])
  })

  it('scanners catch violations in inline CSS', () => {
    const css = '/* #fff */\n.a { color: #1a2b3c; }\n.b { font-size: 14px; }\n.c { font-size: var(--text-sm); }'
    expect(scanners['hex-color'](css)).toEqual(['.a { color: #1a2b3c; }'])
    expect(scanners['raw-font-size'](css)).toEqual(['.b { font-size: 14px; }'])
  })

  it('font-size scanner handles spacing, case, !important, units and the font shorthand', () => {
    const hits = (css: string) => scanners['raw-font-size'](css).length
    expect(hits('.a { font-size : 14px; }')).toBe(1)
    expect(hits('.a { FONT-SIZE:14px; }')).toBe(1)
    expect(hits('.a { font-size:var(--text-sm); }')).toBe(0)
    expect(hits('.a { font-size:inherit; }')).toBe(0)
    expect(hits('.a { font-size: var(--text-sm); font-size: 14px; }')).toBe(1)
    expect(hits('.a { font-size: var(--text-sm); color: red; }')).toBe(0)
    expect(hits('.a { font-size: 1.5em; }')).toBe(1)
    expect(hits('.a { font-size: 14px !important; }')).toBe(1)
    expect(hits('.a { font: 14px/1.2 sans-serif; }')).toBe(1)
    expect(hits('.a { FONT : bold 12px serif; }')).toBe(1)
    expect(hits('.a { font: inherit; }')).toBe(0)
    expect(hits('.a { font-family: serif; }')).toBe(0)
  })

  for (const rule of ['hex-color', 'raw-font-size'] as const) {
    const label = rule === 'hex-color' ? 'hex colors' : 'raw font sizes'
    it(`has no new ${label} outside shared/styles`, () => {
      const actual = countsFor(rule)
      const allowed = Object.fromEntries(Object.entries(allowlist).filter(([k]) => k.startsWith(`${rule} `)))
      // Fails on new violations and on stale allowlist entries alike.
      expect(actual).toEqual(allowed)
    })
  }

  // Deployments feature (shipr-pir): spacing and radii come from tokens, colors never derive from currentColor/AccentColor.
  const deploymentSheets = allSheets.filter((s) => s.path.startsWith('/features/deployments/'))

  it('scans the deployments stylesheets', () => {
    expect(deploymentSheets.length).toBeGreaterThan(10)
  })

  it('has no raw px spacing or radii in deployments CSS', () => {
    const raw = /(?:^|[;{\s])(?:gap|row-gap|column-gap|padding(?:-[a-z]+)?|margin(?:-[a-z]+)?|border-radius|top|right|bottom|left)\s*:[^;{}]*\b\d+(?:\.\d+)?px/m
    const offenders = deploymentSheets.filter((s) => raw.test(strip(s.css))).map((s) => s.path)
    expect(offenders).toEqual([])
  })

  it('has no currentColor or AccentColor in deployments CSS', () => {
    const offenders = deploymentSheets.filter((s) => /currentColor|AccentColor/.test(strip(s.css))).map((s) => s.path)
    expect(offenders).toEqual([])
  })
})
