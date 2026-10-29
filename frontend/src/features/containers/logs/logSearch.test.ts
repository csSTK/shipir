import { describe, expect, it, vi } from 'vitest'
import { findMatches, MatchCache } from './logSearch.ts'

describe('findMatches', () => {
  it('finds case-insensitive substrings with their ranges', () => {
    expect(findMatches('Error: error ERROR', 'error')).toEqual([
      { start: 0, end: 5 },
      { start: 7, end: 12 },
      { start: 13, end: 18 },
    ])
  })

  it('returns nothing for an empty query', () => {
    expect(findMatches('anything', '')).toEqual([])
  })

  it('treats regex characters literally', () => {
    expect(findMatches('a.b a+b axb', 'a.b')).toEqual([{ start: 0, end: 3 }])
    expect(findMatches('x (y) z', '(y)')).toEqual([{ start: 2, end: 5 }])
  })

  it('does not report overlapping matches', () => {
    expect(findMatches('aaaa', 'aa')).toEqual([
      { start: 0, end: 2 },
      { start: 2, end: 4 },
    ])
  })
})

describe('MatchCache', () => {
  const lines = [
    { seq: 1, text: 'a foo' },
    { seq: 2, text: 'bar' },
    { seq: 3, text: 'foo foo' },
  ]

  it('reports ranges, matched lines and the total', () => {
    const r = new MatchCache().search(lines, 'foo')
    expect(r.total).toBe(3)
    expect(r.matched).toEqual([{ seq: 1, count: 1 }, { seq: 3, count: 2 }])
    expect(r.ranges.has(2)).toBe(false)
  })

  it('searches only unseen lines, forgets dropped ones and restarts on a new query', () => {
    const find = vi.fn(findMatches)
    const cache = new MatchCache(find)
    cache.search(lines, 'foo')
    expect(find).toHaveBeenCalledTimes(3)
    find.mockClear()
    cache.search([...lines.slice(1), { seq: 4, text: 'foo' }], 'foo')
    expect(find).toHaveBeenCalledTimes(1)
    find.mockClear()
    cache.search(lines, 'foo') // seq 1 was forgotten, 2 and 3 are cached
    expect(find).toHaveBeenCalledTimes(1)
    find.mockClear()
    cache.search(lines, 'bar')
    expect(find).toHaveBeenCalledTimes(3)
  })

  it('returns nothing for an empty query', () => {
    expect(new MatchCache().search(lines, '').total).toBe(0)
  })
})
