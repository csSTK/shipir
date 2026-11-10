import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

const css = readFileSync(join(process.cwd(), 'src/shared/ui/PageHeader.css'), 'utf8')
const rule = (selector: string) => new RegExp(`^${selector.replace('.', '\\.')}\\s*\\{([^}]*)\\}`, 'm').exec(css)?.[1] ?? ''

// Vitest does not apply CSS, so the alignment is checked in the stylesheet itself.
describe('PageHeader styles', () => {
  it.each(['.ui-pageheader', '.ui-pageheader__actions'])('%s centers its items on one line', (selector) => {
    expect(rule(selector)).toMatch(/align-items:\s*center/)
    expect(rule(selector)).not.toMatch(/flex-end/)
  })
})
