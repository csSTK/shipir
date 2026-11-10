import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'
import { PHONE_MAX_WIDTH, PHONE_QUERY } from './breakpoints.ts'

const srcRoot = join(dirname(fileURLToPath(import.meta.url)), '../..')
const read = (rel: string) => readFileSync(join(srcRoot, rel), 'utf8')

describe('phone breakpoint', () => {
  it('builds the media query from the width', () => {
    expect(PHONE_QUERY).toBe(`(max-width: ${PHONE_MAX_WIDTH}px)`)
  })

  it.each(['app/layout/AppShell.tsx', 'shared/ui/ResourceTable.tsx', 'features/containers/details/ContainerDetailsPage.tsx', 'features/deployments/diff/VersionDiff.tsx'])(
    '%s uses the shared constant instead of a literal',
    (file) => {
      expect(read(file)).not.toMatch(/max-width:\s*720px/)
    },
  )

  it('keeps the CSS media queries in step with the constant', () => {
    for (const file of ['app/layout/AppShell.css', 'app/layout/Sidebar.css']) {
      expect(read(file)).toContain(`max-width: ${PHONE_MAX_WIDTH}px`)
    }
  })
})
