import { readdirSync, readFileSync } from 'node:fs'
import { dirname, join, relative } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'

// All CSS outside shared/styles (features, app, shared/ui, shared/charts) may only use design tokens:
// no hex colors, no raw font sizes (incl. the font shorthand), no raw px spacing/radii/insets/max sizes,
// no currentColor/AccentColor, and the wizard stylesheet has no decimal opacity, raw durations or bare easings.
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

// The hex-color and raw-font-size allowlist is empty. Add an entry only with a bead reference and a reason
// in a comment; a stale entry (count no longer matches) fails the test, so the list can only shrink.
// Key: `<rule> <file>` -> number of offending lines.
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

  // All CSS outside shared/styles (shipr-ycvc.32): spacing, radii and layout sizes come from tokens,
  // colors never derive from currentColor/AccentColor. Media-query breakpoints are not declarations and stay raw.
  const RAW_PX =
    /(?:^|[;{\s])(?:gap|row-gap|column-gap|padding(?:-[a-z]+)?|margin(?:-[a-z]+)?|border-radius|inset(?:-[a-z]+)*|top|right|bottom|left|max-width|max-height)\s*:[^;{}]*?-?\b\d+(?:\.\d+)?px[^;{}]*/gim
  const rawPx = (css: string): string[] => [...strip(css).matchAll(RAW_PX)].map((m) => m[0].replace(/^[;{\s]+/, '').trim())
  const derivedColor = (css: string): string[] =>
    strip(css).split('\n').filter((l) => /currentColor|AccentColor/.test(l)).map((l) => l.trim())

  it('px and color scanners catch violations in inline CSS', () => {
    expect(rawPx('.a { gap: 8px; }\n.b { margin: -1px; }\n.c { max-width: 960px; }\n.d { padding: 0 var(--space-2); }')).toEqual([
      'gap: 8px',
      'margin: -1px',
      'max-width: 960px',
    ])
    expect(rawPx('.a { border-radius: 10px; }\n.b { padding: 0\n 4px; }')).toHaveLength(2)
    expect(rawPx('@media (max-width: 720px) { .a { gap: var(--space-2); } }\n.b { width: 1px; }')).toEqual([])
    expect(rawPx('.a { GAP: 8PX; }')).toEqual(['GAP: 8PX'])
    expect(rawPx('.a { inset: 8px; }\n.b { inset-inline-start: 4px; }\n.c { padding:0 8px; }')).toEqual([
      'inset: 8px',
      'inset-inline-start: 4px',
      'padding:0 8px',
    ])
    expect(rawPx('.a { max-width: calc(100% - 8px); }')).toHaveLength(1)
    expect(rawPx('.a { color: red; gap: 8px; margin: 0; }')).toEqual(['gap: 8px'])
    expect(rawPx('.a { padding: var(--space-2); inset: 0; margin-bottom: calc(var(--border-width) * -1); clip-path: inset(50%); }')).toEqual([])
    expect(rawPx('@media (max-width: 600px) { .a { padding: var(--space-2); } }')).toEqual([])
    expect(derivedColor('.a { color: AccentColor; }\n.b { border: 2px solid currentColor; }\n.c { color: red; }')).toHaveLength(2)
  })

  it('has no raw px spacing, radii or layout sizes in any stylesheet outside shared/styles', () => {
    const offenders = Object.fromEntries(checked.flatMap((s) => (rawPx(s.css).length > 0 ? [[s.path, rawPx(s.css)] as const] : [])))
    expect(offenders).toEqual({})
  })

  it('has no currentColor or AccentColor in any stylesheet outside shared/styles', () => {
    const offenders = Object.fromEntries(checked.flatMap((s) => (derivedColor(s.css).length > 0 ? [[s.path, derivedColor(s.css)] as const] : [])))
    expect(offenders).toEqual({})
  })

  // Wizard (former shipr-ycvc.17): no decimal opacity, no raw durations, no bare easing keywords.
  const wizardViolations = (css: string): string[] => {
    const c = strip(css).replace(/var\([^)]*\)/g, '')
    return [
      ...c.matchAll(/opacity\s*:\s*0?\.\d+/gi),
      ...c.matchAll(/(?<![\w.-])\d*\.?\d+m?s\b/gi),
      ...c.matchAll(/\b(?:ease-in-out|ease-in|ease-out|ease|linear)\b|cubic-bezier/gi),
    ].map((m) => m[0])
  }

  it('wizard violation scanner catches decimal opacity, raw durations and bare easings', () => {
    expect(wizardViolations('.a { opacity: 0.65; }')).toEqual(['opacity: 0.65'])
    expect(wizardViolations('.a { transition: opacity 150ms ease-out; }')).toHaveLength(2)
    expect(wizardViolations('.a { transition: x .2s linear; }')).toHaveLength(2)
    expect(wizardViolations('.a { transition: x 1s cubic-bezier(0, 0, 1, 1); }')).toHaveLength(2)
    expect(wizardViolations('.a { transition: x var(--duration-fast) var(--ease-out); opacity: 0; }')).toEqual([])
  })

  it('wizard stylesheet has no decimal opacity, raw durations or bare easing keywords', () => {
    const wizard = allSheets.find((s) => s.path === '/features/deployments/wizard/wizard.css')
    expect(wizard).toBeDefined()
    expect(wizardViolations(wizard!.css)).toEqual([])
  })

  // Assistant dock badge (shipr-ycvc.20): count text on the warn fill must reach WCAG AA 4.5:1 in every theme.
  // tokens.css defines each theme block once (light, OS dark, forced dark), so the values are read in that order.
  describe('assistant dock tokens', () => {
    const tokensCss = readFileSync(join(srcRoot, 'shared/styles/tokens.css'), 'utf8')
    const tokenValues = (name: string) =>
      [...tokensCss.matchAll(new RegExp(`--${name}:\\s*(#[0-9a-fA-F]{6})\\b`, 'g'))].map((m) => m[1])
    const luminance = (hex: string) => {
      const [r, g, b] = [1, 3, 5].map((i) => {
        const c = parseInt(hex.slice(i, i + 2), 16) / 255
        return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4
      })
      return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }
    const contrast = (a: string, b: string) => {
      const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x)
      return (hi + 0.05) / (lo + 0.05)
    }

    it('badge count text on the warn fill meets 4.5:1 in light, OS dark and forced dark', () => {
      const fills = tokenValues('color-warn-solid')
      const texts = tokenValues('color-on-warn-solid')
      expect(fills).toHaveLength(3)
      expect(texts).toHaveLength(3)
      fills.forEach((fill, i) => expect(contrast(texts[i], fill)).toBeGreaterThanOrEqual(4.5))
    })

    it('badge uses the warn text token, not white', () => {
      const dock = allSheets.find((s) => s.path === '/features/ai-chat/AssistantDock.css')
      expect(dock).toBeDefined()
      const badge = strip(dock!.css).match(/\.assistant-dock__badge\s*\{([^}]*)\}/)?.[1] ?? ''
      expect(badge).toMatch(/color:\s*var\(--color-on-warn-solid\)/)
    })

    it('dock stylesheet takes spin duration, border width and opacity from tokens', () => {
      const dock = allSheets.find((s) => s.path === '/features/ai-chat/AssistantDock.css')
      expect(dock).toBeDefined()
      const c = strip(dock!.css).replace(/var\([^)]*\)/g, '')
      expect([...c.matchAll(/opacity\s*:\s*0?\.\d+/gi), ...c.matchAll(/(?<![\w.-])\d*\.?\d+m?s\b/gi)].map((m) => m[0])).toEqual([])
      const rawBorders = [...strip(dock!.css).matchAll(/border(?:-[a-z]+)?\s*:[^;{}]*?\b\d+px\b[^;{}]*/gi)].map((m) => m[0])
      expect(rawBorders).toEqual([])
    })
  })
})
