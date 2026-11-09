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

// Raw-literal rules (shipr-ycvc.44): border/outline widths, width/height/min-* sizes, grid tracks, translate offsets,
// decimal opacity, numeric font-weight and stroke-width must come from tokens. Key: `<rule> <file>` -> offending lines.
// Allowlist entries are legitimate literals only; a stale entry (count no longer matches) fails the test.
// shipr-ycvc.54 adds outline-offset, line-height, flex-basis, scale, var() fallbacks, custom properties, percentage
// opacity and raw durations. Gaps consciously left open (each with its reason):
//  - rem/em widths and flex-basis (non-square): font-relative layout measures that scale with the user's font size;
//    only square rem/em icon boxes are tokenized (iconBox rule below) and flex-basis is checked for px only.
//  - letter-spacing em: typographic tracking is relative to the font size by design.
//  - bare easing keywords (ease, linear, ...): scanned by their own rule (shipr-k31n.36) with a counted allowlist of
//    existing debt; only --ease-out is tokenized so far.
//  - inline style values in TSX driven by data (DiskUsageCard % segment widths, ResourceTable column sizes,
//    Skeleton/YamlEditor width/height props, chart colors): computed at runtime, no token can express them.
type LiteralRule =
  | 'border-width'
  | 'size'
  | 'grid-track'
  | 'translate'
  | 'opacity'
  | 'font-weight'
  | 'stroke-width'
  | 'outline-offset'
  | 'line-height'
  | 'flex-basis'
  | 'scale'
  | 'var-fallback'
  | 'custom-property'
  | 'opacity-percent'
  | 'duration'

const decl = (names: string, value: string) => new RegExp(`(?:^|[;{\\s])(?:${names})\\s*:[^;{}]*?${value}[^;{}]*`, 'gim')
const literalPatterns: Record<LiteralRule, RegExp> = {
  'border-width': decl('border(?:-(?:top|right|bottom|left|inline|block)(?:-(?:start|end))?)?(?:-width)?|outline(?:-width)?', '(?<![\\w.-])\\d*\\.?\\d+px'),
  size: decl('width|height|min-width|min-height', '(?<![\\w.-])\\d*\\.?\\d+px'),
  'grid-track': decl('grid-template-(?:columns|rows)|grid-auto-(?:columns|rows)', '(?<![\\w.-])\\d*\\.?\\d+px'),
  translate: /(?:^|[;{\s])(?:transform|translate)\s*:[^;{}]*?translate[XYZ]?\([^)]*?\d+(?:\.\d+)?px[^;{}]*/gim,
  opacity: decl('opacity', '\\s*0?\\.\\d+'),
  'font-weight': decl('font-weight', '(?<![\\w-])(?:\\d{3}|bold|bolder|lighter)\\b'),
  'stroke-width': decl('stroke-width', '\\s*\\d'),
  'outline-offset': decl('outline-offset', '(?<![\\w.-])\\d*\\.?\\d+px'),
  'line-height': decl('line-height', '(?<![\\w.-])\\d*\\.?\\d+(?:px|rem|em)\\b'),
  'flex-basis': decl('flex(?:-basis)?', '(?<![\\w.-])\\d*\\.?\\d+px'),
  scale: /(?<![\w-])scale[XYZ]?\(\s*-?\.?\d[^)]*\)/gim,
  'var-fallback': /var\(\s*--[\w-]+\s*,[^)]*?(?:(?<![\w.-])\d*\.?\d+(?:px|rem|em|vh|vw|dvh|ms|s)\b|#[0-9a-fA-F]{3,8}\b)[^)]*\)/gim,
  'custom-property': /(?:^|[;{\s])--[\w-]+\s*:[^;{}]*?(?:(?<![\w.-])\d*\.?\d+(?:px|rem|em|ms|s)\b|#[0-9a-fA-F]{3,8}\b)[^;{}]*/gim,
  'opacity-percent': decl('opacity', '\\s*\\d+(?:\\.\\d+)?%'),
  duration: decl('transition(?:-duration|-delay)?|animation(?:-duration|-delay)?', '(?<![\\w.-])\\d*\\.?\\d+m?s\\b'),
}
const literalHits = (rule: LiteralRule, css: string): string[] =>
  [...strip(css).matchAll(literalPatterns[rule])].map((m) => m[0].replace(/^[;{\s]+/, '').trim())

// Visually-hidden pattern (position absolute, 1px box, clip-path) is a fixed accessibility idiom, not a design size;
// the 1px table cell is the shrink-to-fit trick for the sticky actions column.
// Square icon boxes (width == height, same rem/em literal in one rule) must use --size-icon-*.
const iconBoxHits = (css: string): string[] =>
  [...strip(css).matchAll(/\{[^{}]*\}/g)].flatMap(([block]) => {
    const w = block.match(/(?<![\w-])width\s*:\s*(\d*\.?\d+(?:rem|em))\s*;/i)?.[1]
    const h = block.match(/(?<![\w-])height\s*:\s*(\d*\.?\d+(?:rem|em))\s*;/i)?.[1]
    return w && w === h ? [`${w} x ${h}`] : []
  })
// Key: file -> count. Allowlisted entries are control internals, not icons.
const iconBoxAllowlist: Record<string, number> = {
  '/shared/ui/Button.css': 1, // spinner sized in em so it scales with the button's font size
  '/shared/ui/Switch.css': 1, // switch thumb, control internal
}

const literalAllowlist: Record<string, number> = {
  'size /features/ai-chat/ChatPane.css': 2, // visually-hidden composer label
  'size /shared/ui/FormField.css': 2, // visually-hidden bare label
  'var-fallback /shared/ui/ResourceTable.css': 1, // --rtable-max-height is the table's public API; 70vh is its viewport-relative default
  'size /shared/ui/ResourceTable.css': 3, // visually-hidden .rtable__sr (2) + shrink-to-fit actions cell width (1)
}

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

  // Bare easing keywords and cubic-bezier() outside shared/styles must come from --ease-* tokens (shipr-ycvc.54).
  const bareEasing = (css: string): string[] =>
    [...strip(css).replace(/var\([^)]*\)/g, '').matchAll(/(?<![\w-])(?:ease-in-out|ease-in|ease-out|ease|linear)(?![\w-])|cubic-bezier\(/gi)].map(
      (m) => m[0],
    )

  it('bare easing scanner catches keywords and ignores tokens and gradients', () => {
    expect(bareEasing('.a { transition: x 1s ease-out; }')).toEqual(['ease-out'])
    expect(bareEasing('.a { animation: spin 1s linear infinite; }')).toEqual(['linear'])
    expect(bareEasing('.a { transition: x 1s cubic-bezier(0,0,1,1); }')).toEqual(['cubic-bezier('])
    expect(bareEasing('.a { transition: x var(--duration-fast) var(--ease-out); background: linear-gradient(red, blue); }')).toEqual([])
  })

  // Debt, not accepted: these stylesheets still use bare keywords. Only --ease-out is tokenized (tokens.css), so the
  // rest needs a design decision for --ease-in-out, --ease and linear (follow-up bead). Counts must match exactly.
  const bareEasingAllowlist: Record<string, number> = {
    '/features/ai-chat/AssistantDock.css': 1,
    '/features/ai-chat/ChatPane.css': 1,
    '/features/deployments/DeploymentDetailPage.css': 1,
    '/features/deployments/config-files/ConfigFilesPanel.css': 2,
    '/features/deployments/deploy-output/DeployOutput.css': 3,
    '/features/deployments/editor/editor.css': 1,
    '/features/deployments/templates/templates.css': 2,
    '/shared/ui/Button.css': 1,
    '/shared/ui/Skeleton.css': 1,
  }

  it('has no bare easing keywords outside shared/styles beyond the allowlist', () => {
    const actual = Object.fromEntries(checked.flatMap((s) => (bareEasing(s.css).length > 0 ? [[s.path, bareEasing(s.css).length] as const] : [])))
    expect(actual).toEqual(bareEasingAllowlist)
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

  it('literal scanners catch raw values and ignore tokens', () => {
    const h = literalHits
    expect(h('border-width', '.a { border: 1px solid var(--color-border); }')).toHaveLength(1)
    expect(h('border-width', '.a { border-top: 2px dashed red; outline: 1px solid red; border-left-width: 3px; }')).toHaveLength(3)
    expect(h('border-width', '.a { border: var(--border-width) solid red; border-radius: 4px; border-top-left-radius: 4px; outline-offset: 2px; }')).toEqual([])
    expect(h('size', '.a { width: 16px; min-height: 2px; }\n.b { height: 1px; }')).toHaveLength(3)
    expect(h('size', '.a { width: var(--size-icon); max-width: 10px; line-height: 1px; border-width: 0; }')).toEqual([])
    expect(h('grid-track', '.a { grid-template-columns: minmax(0, 1fr) 90px auto; }')).toHaveLength(1)
    expect(h('grid-track', '.a { grid-template-columns: repeat(2, 1fr); }')).toEqual([])
    expect(h('translate', '.a { transform: translateY(4px); }')).toHaveLength(1)
    expect(h('translate', '.a { transform: translate(-50%, 4px) scale(1); }')).toHaveLength(1)
    expect(h('translate', '.a { transform: translateY(var(--space-1)) rotate(4deg); }')).toEqual([])
    expect(h('opacity', '.a { opacity: 0.6; }\n.b { opacity: .5; }')).toHaveLength(2)
    expect(h('opacity', '.a { opacity: var(--opacity-dimmed); opacity: 0; opacity: 1; }')).toEqual([])
    expect(h('font-weight', '.a { font-weight: 600; font-weight: bold; }')).toHaveLength(2)
    expect(h('font-weight', '.a { font-weight: var(--weight-semibold); font-weight: inherit; }')).toEqual([])
    expect(h('stroke-width', '.a { stroke-width: 2; }')).toHaveLength(1)
    expect(h('stroke-width', '.a { stroke-width: var(--border-width); }')).toEqual([])
  })

  for (const rule of Object.keys(literalPatterns) as LiteralRule[]) {
    it(`has no raw ${rule} literals outside shared/styles beyond the allowlist`, () => {
      const actual = Object.fromEntries(
        checked.flatMap((s) => {
          const n = literalHits(rule, s.css).length
          return n > 0 ? [[`${rule} ${s.path}`, n] as const] : []
        }),
      )
      const allowed = Object.fromEntries(Object.entries(literalAllowlist).filter(([k]) => k.startsWith(`${rule} `)))
      expect(actual).toEqual(allowed)
    })
  }

  it('new literal scanners (shipr-ycvc.54) catch raw values and ignore tokens', () => {
    const h = literalHits
    expect(h('outline-offset', '.a { outline-offset: 2px; }')).toHaveLength(1)
    expect(h('outline-offset', '.a { outline-offset: var(--focus-offset); outline-offset: 0; }')).toEqual([])
    expect(h('line-height', '.a { line-height: 20px; }\n.b { line-height: 1.25rem; }\n.c { line-height: 2em; }')).toHaveLength(3)
    expect(h('line-height', '.a { line-height: 1.4; line-height: var(--text-small-leading); line-height: 1; }')).toEqual([])
    expect(h('flex-basis', '.a { flex-basis: 120px; }\n.b { flex: 1 1 120px; }')).toHaveLength(2)
    expect(h('flex-basis', '.a { flex: 1 1 8rem; flex-basis: 100%; flex: 1; }')).toEqual([])
    expect(h('scale', '.a { transform: scale(0.97); }\n.b { transform: translateY(1px) scale(.9); scale: 1.2; }')).toHaveLength(2)
    expect(h('scale', '.a { transform: scale(var(--press-scale)); }')).toEqual([])
    expect(h('var-fallback', '.a { max-height: var(--x, 70vh); color: var(--y, #fff); gap: var(--z, 8px); }')).toHaveLength(3)
    expect(h('var-fallback', '.a { color: var(--y, var(--color-text)); gap: var(--z, 0); border: 2px solid var(--b, var(--c)); }')).toEqual([])
    expect(h('custom-property', '.a { --w: 3.5rem; --c: #abc; --d: 200ms; --p: 4px; }')).toHaveLength(4)
    expect(h('custom-property', '.a { --w: var(--size-icon-lg); --n: 3; --pad: calc(var(--space-2) * 2); width: 3rem; }')).toEqual([])
    expect(h('opacity-percent', '.a { opacity: 50%; }')).toHaveLength(1)
    expect(h('opacity-percent', '.a { opacity: var(--opacity-dimmed); opacity: 0; width: 50%; }')).toEqual([])
    expect(h('duration', '.a { animation: pulse 1.4s ease infinite; transition: x 150ms; animation-duration: 2s; }')).toHaveLength(3)
    expect(h('duration', '.a { animation: pulse var(--duration-pulse) ease infinite; transition: x var(--duration-fast); animation: none; }')).toEqual([])
  })

  // Inline style={{...}} in TSX: size literals (px/rem/em, incl. template strings like `${n}px`) must come from CSS tokens.
  const tsxFiles = (dir: string): string[] =>
    readdirSync(dir, { withFileTypes: true }).flatMap((e) =>
      e.isDirectory() ? tsxFiles(join(dir, e.name)) : /\.tsx$/.test(e.name) && !/\.test\.tsx$/.test(e.name) ? [join(dir, e.name)] : [],
    )
  // Each style={{...}} attribute is read up to its matching brace (shipr-ycvc.54), so multi-line objects are scanned
  // too and the scan never runs into the JSX after the attribute. Returns the offending lines of each attribute.
  const sizeLiteral = /(?<![\w.-])\d*\.?\d+(?:px|rem|em)\b|\}(?:px|rem|em)\b/
  const inlineStyleLiterals = (tsx: string): string[] =>
    [...tsx.matchAll(/style=\{\{/g)].flatMap((m) => {
      const open = m.index + 'style='.length
      let depth = 0
      let end = open
      for (; end < tsx.length; end++) {
        if (tsx[end] === '{') depth++
        else if (tsx[end] === '}' && --depth === 0) break
      }
      return tsx
        .slice(open, end + 1)
        .split('\n')
        .filter((line) => sizeLiteral.test(line))
        .map((line) => line.trim())
    })

  it('inline style scanner catches size literals and ignores runtime values', () => {
    expect(inlineStyleLiterals('<li style={{ paddingInlineStart: `${depth * 14}px` }}>')).toHaveLength(1)
    expect(inlineStyleLiterals("<div style={{ height: '12rem' }} />")).toHaveLength(1)
    expect(inlineStyleLiterals('<div style={{ width, height }} />')).toEqual([])
    expect(inlineStyleLiterals("<li style={{ '--depth': depth } as CSSProperties}>")).toEqual([])
  })

  it('inline style scanner reads multi-line style objects and stops at the attribute end', () => {
    expect(inlineStyleLiterals("<div style={{\n  width: '12rem',\n  color: 'red',\n}} />")).toHaveLength(1)
    expect(inlineStyleLiterals("<li style={{\n  '--depth': depth,\n  paddingInlineStart: `${depth * 14}px`,\n} as CSSProperties}>")).toHaveLength(1)
    expect(inlineStyleLiterals('<div style={{ width, height }} />\n<p>12px</p>')).toEqual([])
  })

  it('has no px/rem/em literals in inline styles of any component', () => {
    const offenders = Object.fromEntries(
      tsxFiles(srcRoot).flatMap((f) => {
        const hits = inlineStyleLiterals(readFileSync(f, 'utf8'))
        return hits.length > 0 ? [[relative(srcRoot, f), hits] as const] : []
      }),
    )
    expect(offenders).toEqual({})
  })

  it('icon box scanner flags square rem/em boxes only', () => {
    expect(iconBoxHits('.a { width: 1.5rem; height: 1.5rem; }')).toHaveLength(1)
    expect(iconBoxHits('.a { width: 1.5rem; height: 2rem; }')).toEqual([])
    expect(iconBoxHits('.a { width: var(--size-icon-lg); height: var(--size-icon-lg); }')).toEqual([])
    expect(iconBoxHits('.a { min-width: 1rem; line-height: 1rem; }')).toEqual([])
  })

  it('has no square rem/em icon boxes outside shared/styles beyond the allowlist', () => {
    const actual = Object.fromEntries(checked.flatMap((s) => (iconBoxHits(s.css).length ? [[s.path, iconBoxHits(s.css).length] as const] : [])))
    expect(actual).toEqual(iconBoxAllowlist)
  })

  // Every token defined in tokens.css must be referenced by a stylesheet or component (css/ts/tsx under src,
  // tests excluded), or be on unusedTokenAllowlist with a reason. A stale entry (token gone or now used) fails.
  describe('every defined token is used (shipr-ycvc.57)', () => {
    const tokenNames = [
      ...new Set([...strip(readFileSync(join(srcRoot, 'shared/styles/tokens.css'), 'utf8')).matchAll(/^\s*(--[\w-]+)\s*:/gm)].map((m) => m[1])),
    ]
    const usageSources = (dir: string): string[] =>
      readdirSync(dir, { withFileTypes: true }).flatMap((e) =>
        e.isDirectory()
          ? usageSources(join(dir, e.name))
          : /\.(?:css|tsx?)$/.test(e.name) && !/\.test\.tsx?$/.test(e.name) && e.name !== 'tokens.css'
            ? [readFileSync(join(dir, e.name), 'utf8')]
            : [],
      )
    const sources = usageSources(srcRoot)
    // Names are matched whole: --size-icon-s does not count as a use of --size-icon-sm.
    const unusedTokens = (names: string[], corpus: string[]): string[] =>
      names.filter((n) => !corpus.some((src) => new RegExp(`${n}(?![\\w-])`).test(src)))

    // Documented mirrors of the JS spring presets in shared/ui/motion.ts (values match) that the apple-design skill
    // names. Kept as design reference; CSS does not use them.
    const unusedTokenAllowlist: Record<string, string> = {
      '--spring-snappy': 'documented mirror of springSnappy in shared/ui/motion.ts (stiffness 520, damping 38, mass 1)',
      '--spring-smooth': 'documented mirror of springSmooth in shared/ui/motion.ts (stiffness 300, damping 30, mass 1)',
      '--spring-gentle': 'documented mirror of springGentle in shared/ui/motion.ts (stiffness 180, damping 24, mass 1)',
    }

    it('scans enough tokens and sources (never vacuous)', () => {
      expect(tokenNames.length).toBeGreaterThan(50)
      expect(sources.length).toBeGreaterThan(MIN_SHEETS)
    })

    it('unusedTokens catches an unused name and ignores prefixes of longer names', () => {
      expect(unusedTokens(['--size-icon-sm', '--size-checkbox'], ['width: var(--size-icon-s);', 'x: var(--size-icon-sm)'])).toEqual([
        '--size-checkbox',
      ])
      expect(unusedTokens(['--a'], ['var(--a-b)'])).toEqual(['--a'])
    })

    it('every token is used by a source file or allowlisted with a reason', () => {
      const offenders = unusedTokens(tokenNames, sources).filter((n) => !(n in unusedTokenAllowlist))
      expect(offenders).toEqual([])
    })

    it('unused-token allowlist has no stale entries', () => {
      const stale = Object.keys(unusedTokenAllowlist).filter((n) => !tokenNames.includes(n) || unusedTokens([n], sources).length === 0)
      expect(stale).toEqual([])
    })
  })

  describe('one categorical palette (shipr-anxl.3)', () => {
    const tokens = allSheets.find((s) => s.path === '/shared/styles/tokens.css')?.css ?? ''
    const countOf = (name: string) => (strip(tokens).match(new RegExp(`${name}\\s*:`, 'g')) ?? []).length

    it('defines --color-series-1..8 in light, system dark and forced dark, and no --color-cat set', () => {
      expect(strip(tokens)).not.toMatch(/--color-cat-/)
      for (let i = 1; i <= 8; i++) expect(countOf(`--color-series-${i}`)).toBe(3)
    })

    it('no stylesheet references the removed --color-cat tokens', () => {
      expect(allSheets.filter((s) => /--color-cat-/.test(s.css)).map((s) => s.path)).toEqual([])
    })

    it('disk usage segments use the series tokens', () => {
      const disk = allSheets.find((s) => s.path === '/features/hosts/DiskUsageCard.css')
      expect(disk).toBeDefined()
      expect(strip(disk!.css)).toMatch(/var\(--color-series-\d\)/)
    })
  })
})
