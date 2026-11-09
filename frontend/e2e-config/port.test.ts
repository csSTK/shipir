import { describe, expect, it } from 'vitest'
import { e2ePort, ownerFromStatus, ownershipProbePath } from './port'

describe('dev server ownership marker (shipr-k31n.36)', () => {
  it('asks for this checkout\'s own index.html through the Vite /@fs/ route', () => {
    expect(ownershipProbePath('/work/shipir-x/frontend')).toBe('/@fs/work/shipir-x/frontend/index.html')
  })

  it('reads 200 as this checkout, no answer as a free port and any other status as another server', () => {
    expect(ownerFromStatus(200)).toBe('this-checkout')
    expect(ownerFromStatus(0)).toBe('free')
    expect(ownerFromStatus(403)).toBe('other')
    expect(ownerFromStatus(404)).toBe('other')
  })
})

describe('e2ePort', () => {
  it('is stable for the same checkout', () => {
    expect(e2ePort('/work/shipir/frontend')).toBe(e2ePort('/work/shipir/frontend'))
  })

  it('differs between the main checkout and a worktree', () => {
    expect(e2ePort('/work/shipir/frontend')).not.toBe(e2ePort('/work/shipir-shipr-kqbc.20/frontend'))
  })

  it('stays in the 20000-39999 range, away from the 5173 of a plain `pnpm dev`', () => {
    for (const dir of ['/a', '/b', '/work/shipir/frontend', '/work/shipir-x/frontend']) {
      const port = e2ePort(dir)
      expect(port).toBeGreaterThanOrEqual(20_000)
      expect(port).toBeLessThan(40_000)
    }
  })

  it('lets E2E_PORT override the derived port', () => {
    expect(e2ePort('/work/shipir/frontend', '5190')).toBe(5190)
  })

  it('ignores an empty override and rejects an invalid one', () => {
    expect(e2ePort('/a', '')).toBe(e2ePort('/a'))
    expect(() => e2ePort('/a', 'abc')).toThrow(/E2E_PORT/)
    expect(() => e2ePort('/a', '70000')).toThrow(/E2E_PORT/)
  })
})
