import { describe, expect, it } from 'vitest'
import { e2ePort, resolveRunPort } from './port'

describe('resolveRunPort (shipr-k31n.61)', () => {
  it('gives every run its own free port instead of a shared derived one', () => {
    const ports = [31_000, 31_001]
    const first = resolveRunPort('/work/a', {}, 'E2E_PORT', { freePort: () => ports[0] })
    const second = resolveRunPort('/work/a', {}, 'E2E_PORT', { freePort: () => ports[1] })
    expect(first).not.toBe(second)
  })

  it('pins the chosen port in E2E_PORT so worker processes loading the config agree with the runner', () => {
    const env: Record<string, string | undefined> = {}
    expect(resolveRunPort('/work/a', env, 'E2E_PORT', { freePort: () => 31_002 })).toBe(31_002)
    expect(env.E2E_PORT).toBe('31002')
    const worker = resolveRunPort('/work/a', env, 'E2E_PORT', { freePort: () => 99 })
    expect(worker).toBe(31_002)
  })

  it('honours an explicit override and validates it', () => {
    const noPort = { freePort: () => { throw new Error('asked the OS') } }
    expect(resolveRunPort('/work/a', { E2E_CSP_PORT: '5190' }, 'E2E_CSP_PORT', noPort)).toBe(5190)
    expect(() => resolveRunPort('/work/a', { E2E_PORT: 'abc' }, 'E2E_PORT', noPort)).toThrow(/E2E_PORT/)
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
