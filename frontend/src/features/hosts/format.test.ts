import { describe, expect, it } from 'vitest'
import { formatSize } from './format.ts'

const KIB = 1024
const MIB = 1024 ** 2
const GIB = 1024 ** 3
const TIB = 1024 ** 4
const PIB = 1024 ** 5

describe('formatSize', () => {
  it('keeps plain bytes and the KiB, MiB and GiB tiers', () => {
    expect(formatSize(0)).toBe('0 B')
    expect(formatSize(512)).toBe('512 B')
    expect(formatSize(KIB)).toBe('1 KiB')
    expect(formatSize(1.5 * KIB)).toBe('1.5 KiB')
    expect(formatSize(MIB)).toBe('1 MiB')
    expect(formatSize(800 * MIB)).toBe('800 MiB')
    expect(formatSize(GIB)).toBe('1 GiB')
    expect(formatSize(1.2 * GIB)).toBe('1.2 GiB')
  })

  it('shows terabytes instead of a large GiB count', () => {
    expect(formatSize(2 * TIB)).toBe('2 TiB')
    expect(formatSize(1.5 * TIB)).toBe('1.5 TiB')
  })

  it('shows pebibytes', () => {
    expect(formatSize(PIB)).toBe('1 PiB')
    expect(formatSize(1.25 * PIB)).toBe('1.3 PiB')
  })

  it('stays in PiB for huge values instead of growing the unit count', () => {
    expect(formatSize(2 ** 61)).toBe('2048 PiB')
  })
})
