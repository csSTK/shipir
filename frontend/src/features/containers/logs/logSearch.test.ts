import { describe, expect, it } from 'vitest'
import { findMatches } from './logSearch.ts'

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
