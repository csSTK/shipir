import { describe, expect, it } from 'vitest'
import { mergeEnv, parseEnv, serializeEnv, validateEnv } from './envFile.ts'

describe('parseEnv', () => {
  it('reads pairs, skips comments and blanks, strips export and quotes', () => {
    const { entries, invalidLines } = parseEnv(
      ['# comment', '', 'A=1', 'export B="two words"', "C='x # y'", 'D=plain # trailing', 'E=', 'F="a\\nb"'].join('\n'),
    )
    expect(invalidLines).toEqual([])
    expect(entries).toEqual([
      { key: 'A', value: '1' },
      { key: 'B', value: 'two words' },
      { key: 'C', value: 'x # y' },
      { key: 'D', value: 'plain' },
      { key: 'E', value: '' },
      { key: 'F', value: 'a\nb' },
    ])
  })

  it('reports lines without = and keeps = inside values', () => {
    const { entries, invalidLines } = parseEnv('OK=a=b\nnonsense\r\nX=1')
    expect(entries).toEqual([
      { key: 'OK', value: 'a=b' },
      { key: 'X', value: '1' },
    ])
    expect(invalidLines).toEqual([2])
  })
})

describe('serializeEnv', () => {
  it('round-trips values that need quoting', () => {
    const entries = [
      { key: 'A', value: 'simple' },
      { key: 'B', value: 'has space "q" # hash' },
      { key: 'C', value: 'line1\nline2' },
      { key: 'D', value: '' },
    ]
    expect(parseEnv(serializeEnv(entries)).entries).toEqual(entries)
  })
})

describe('validateEnv', () => {
  it('flags invalid and duplicate keys, ignores blank rows', () => {
    const issues = validateEnv([
      { key: 'A', value: '' },
      { key: '1BAD', value: '' },
      { key: 'A', value: '' },
      { key: '', value: 'x' },
      { key: 'has space', value: '' },
    ])
    expect(issues.get(0)).toBeUndefined()
    expect(issues.get(1)).toBe('INVALID_KEY')
    expect(issues.get(2)).toBe('DUPLICATE_KEY')
    expect(issues.get(3)).toBeUndefined()
    expect(issues.get(4)).toBe('INVALID_KEY')
  })
})

describe('mergeEnv', () => {
  it('overwrites same keys, appends new, drops empty rows', () => {
    expect(
      mergeEnv(
        [
          { key: 'A', value: '1' },
          { key: '', value: '' },
        ],
        [
          { key: 'A', value: '2' },
          { key: 'B', value: '3' },
        ],
      ),
    ).toEqual([
      { key: 'A', value: '2' },
      { key: 'B', value: '3' },
    ])
  })
})
