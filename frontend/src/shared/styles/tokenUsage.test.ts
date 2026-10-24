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
})
