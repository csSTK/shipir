import { readdirSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'

// Feature and shared/ui CSS may only use design tokens: no hex colors, no raw font sizes.
const sheets = import.meta.glob('/src/**/*.css', { query: '?raw', import: 'default', eager: true }) as Record<string, string>
const checked = Object.entries(sheets).filter(([path]) => !path.startsWith('/src/shared/styles/'))

describe('design token usage', () => {
  it('scans the stylesheets', () => {
    expect(checked.length).toBeGreaterThan(20)
  })

  it('has no hex colors outside shared/styles', () => {
    const offenders = checked.filter(([, css]) => /#[0-9a-fA-F]{3,8}\b/.test(css.replace(/\/\*[\s\S]*?\*\//g, ''))).map(([p]) => p)
    expect(offenders).toEqual([])
  })

  it('has no raw font sizes outside shared/styles', () => {
    const offenders = checked
      .filter(([, css]) => /font-size:\s*(?!var\(--text-|inherit|1em)/.test(css.replace(/\/\*[\s\S]*?\*\//g, '')))
      .map(([p]) => p)
    expect(offenders).toEqual([])
  })

  // Deployments feature (shipr-pir): spacing and radii come from tokens, colors never derive from currentColor/AccentColor.
  const strip = (css: string) => css.replace(/\/\*[\s\S]*?\*\//g, '')
  // Vitest blanks CSS modules (even ?raw), so read the deployments stylesheets from disk.
  const root = join(dirname(fileURLToPath(import.meta.url)), '../../features/deployments')
  const cssFiles = (dir: string): string[] =>
    readdirSync(dir, { withFileTypes: true }).flatMap((e) =>
      e.isDirectory() ? cssFiles(join(dir, e.name)) : e.name.endsWith('.css') ? [join(dir, e.name)] : [],
    )
  const deploymentSheets = cssFiles(root).map((f) => [f, readFileSync(f, 'utf8')] as const)

  it('scans the deployments stylesheets', () => {
    expect(deploymentSheets.length).toBeGreaterThan(10)
  })

  it('has no raw px spacing or radii in deployments CSS', () => {
    const raw = /(?:^|[;{\s])(?:gap|row-gap|column-gap|padding(?:-[a-z]+)?|margin(?:-[a-z]+)?|border-radius|top|right|bottom|left)\s*:[^;{}]*\b\d+(?:\.\d+)?px/m
    const offenders = deploymentSheets.filter(([, css]) => raw.test(strip(css))).map(([p]) => p)
    expect(offenders).toEqual([])
  })

  it('has no currentColor or AccentColor in deployments CSS', () => {
    const offenders = deploymentSheets.filter(([, css]) => /currentColor|AccentColor/.test(strip(css))).map(([p]) => p)
    expect(offenders).toEqual([])
  })
})
