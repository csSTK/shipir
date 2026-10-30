import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

const dir = join(process.cwd(), 'src/features/ai-chat')
const read = (name: string) => readFileSync(join(dir, name), 'utf8')

// The AssistantDock renders ChatPane without ChatPage. If ChatPage is lazy-loaded,
// ChatPane's styles must not depend on ChatPage.css being loaded.
describe('ChatPane styles', () => {
  it('ChatPane imports its own stylesheet', () => {
    expect(read('ChatPane.tsx')).toMatch(/import\s+'\.\/ChatPane\.css'/)
  })

  it.each(['.chat-pane', '.chat-error', '.chat-header', '.chat-composer', '.chat-thinking'])(
    'ChatPane.css defines %s and ChatPage.css does not',
    (selector) => {
      const own = new RegExp(`^${selector.replace('.', '\\.')}[\\s,{:]`, 'm')
      expect(read('ChatPane.css')).toMatch(own)
      expect(read('ChatPage.css')).not.toMatch(own)
    },
  )
})
