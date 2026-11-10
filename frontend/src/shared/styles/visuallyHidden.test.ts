import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'

// The visually-hidden (screen-reader-only) pattern lives once in shared/styles/utilities.css as `.visually-hidden`.
// Feature pages use the class instead of their own copy. Sheets are read from disk because Vitest blanks CSS.
const srcRoot = join(dirname(fileURLToPath(import.meta.url)), '../..')
const read = (rel: string) => readFileSync(join(srcRoot, rel), 'utf8')

const strip = (css: string) => css.replace(/\/\*[\s\S]*?\*\//g, '')

// Returns the declarations of the first top-level rule for `selector`, whitespace-normalised.
const ruleBody = (css: string, selector: string): string | undefined => {
  const match = strip(css).match(new RegExp(`(^|\\})\\s*${selector.replace(/\./g, '\\.')}\\s*\\{([^}]*)\\}`))
  return match?.[2].replace(/\s+/g, ' ').trim()
}

const PAGES = [
  { tsx: 'features/auth/SetupPage.tsx', css: 'features/auth/SetupPage.css', old: 'setup__sr' },
  { tsx: 'features/auth/PasswordResetPage.tsx', css: 'features/auth/PasswordResetPage.css', old: 'reset__sr' },
  { tsx: 'features/account/AccountPage.tsx', css: 'features/account/AccountPage.css', old: 'account__sr' },
  { tsx: 'shared/ui/ResourceTable.tsx', css: 'shared/ui/ResourceTable.css', old: 'rtable__sr' },
  { tsx: 'features/ai-chat/ChatPane.tsx', css: 'features/ai-chat/ChatPane.css', old: 'chat-composer__label' },
]

describe('shared visually-hidden utility', () => {
  it('defines .visually-hidden once in shared/styles with the clip-path pattern and tokens only', () => {
    const body = ruleBody(read('shared/styles/utilities.css'), '.visually-hidden')
    expect(body).toBe(
      'position: absolute; width: var(--border-width); height: var(--border-width); overflow: hidden; clip-path: inset(50%); white-space: nowrap;',
    )
  })

  it('is loaded globally from index.css', () => {
    expect(read('index.css')).toContain("@import './shared/styles/utilities.css';")
  })

  it.each(PAGES)('$tsx uses the shared class instead of its own $old copy', ({ tsx, css, old }) => {
    const source = read(tsx)
    expect(source).toContain('className="visually-hidden"')
    expect(source).not.toContain(old)
    expect(read(css)).not.toContain(`.${old}`)
  })

  it('FormField hides a bare label with the shared class, not its own rule', () => {
    expect(read('shared/ui/FormField.tsx')).toContain('visually-hidden')
    expect(read('shared/ui/FormField.css')).not.toContain('ui-field--bare')
    for (const f of ['TextField', 'Select']) expect(read(`shared/ui/${f}.tsx`)).not.toContain('ui-field--bare')
  })
})
