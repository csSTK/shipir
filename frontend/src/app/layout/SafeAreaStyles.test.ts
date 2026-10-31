import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

const read = (path: string) => readFileSync(join(process.cwd(), 'src', path), 'utf8')

// index.html uses viewport-fit=cover, so content reaches under the notch and the home indicator
// unless the layout pads by the safe-area insets (exposed as --safe-* tokens).
describe('safe-area insets', () => {
  it.each(['right', 'bottom', 'left'])('tokens.css defines --safe-%s from env()', (side) => {
    expect(read('shared/styles/tokens.css')).toContain(`--safe-${side}: env(safe-area-inset-${side}, 0px)`)
  })

  it('the shell pads the bar by the left/right insets and the content by right/bottom (the sidebar takes left)', () => {
    const css = read('app/layout/AppShell.css')
    expect(css).toMatch(/\.shell__bar\s*{[^}]*var\(--safe-right\)[^}]*var\(--safe-left\)/s)
    expect(css).toMatch(/\.shell__content\s*{[^}]*var\(--safe-right\)[^}]*var\(--safe-bottom\)/s)
  })

  it('the narrow layout (no sidebar) pads the content by the left inset too', () => {
    const narrow = read('app/layout/AppShell.css').split('@media (max-width: 720px)')[1]
    expect(narrow).toMatch(/\.shell__content\s*{[^}]*var\(--safe-left\)/s)
  })

  it('the sidebar pads by the left inset', () => {
    expect(read('app/layout/Sidebar.css')).toMatch(/\.sidebar\s*{[^}]*var\(--safe-left\)/s)
  })

  it('the drawer pads by the left and bottom insets', () => {
    expect(read('app/layout/AppShell.css')).toMatch(/\.shell__drawer\s*{[^}]*var\(--safe-bottom\)[^}]*var\(--safe-left\)/s)
  })

  it('the sidebar pads by the bottom inset', () => {
    expect(read('app/layout/Sidebar.css')).toMatch(/\.sidebar\s*{[^}]*var\(--safe-bottom\)/s)
  })

  it('the chat page keeps its height within the bottom inset', () => {
    expect(read('features/ai-chat/ChatPage.css')).toMatch(/\.chat\s*{[^}]*var\(--safe-bottom\)/s)
  })
})
