import { describe, expect, it } from 'vitest'
import { aggregateSeries, alignToBucket, counterRates, formatTick, nearestIndex, niceTicks, splitSegments, summarize, timeTicks } from './series.ts'

const MIN = 60_000

const at = (values: number[], step = 1) => values.map((value, i) => ({ t: i * step * MIN, value }))

describe('counterRates', () => {
  it('divides the delta between buckets by the bucket length', () => {
    expect(counterRates(at([0, 600, 1800]), 60)).toEqual([null, 10, 20])
  })
  it('treats a decrease as a counter reset (gap), never a negative rate', () => {
    expect(counterRates(at([1000, 2000, 50, 110]), 60)).toEqual([null, 1000 / 60, null, 1])
  })
  it('keeps a flat counter at rate 0', () => {
    expect(counterRates(at([5, 5]), 60)).toEqual([null, 0])
  })
  it('returns an empty list for no values', () => {
    expect(counterRates([], 60)).toEqual([])
  })
  it('uses the real time between samples and gaps a hole of more than 1.5 buckets', () => {
    // a hole of 10 buckets must not show up as one inflated bucket
    const pts = [{ t: 0, value: 0 }, { t: 10 * MIN, value: 600 }, { t: 11 * MIN, value: 660 }]
    expect(counterRates(pts, 60)).toEqual([null, null, 1])
    const close = [{ t: 0, value: 0 }, { t: 1.4 * MIN, value: 84 }]
    expect(counterRates(close, 60)[1]).toBeCloseTo(1, 5)
  })
})

describe('alignToBucket', () => {
  it('floors a timestamp to its bucket start', () => {
    expect(alignToBucket(5 * MIN + 17_000, 60)).toBe(5 * MIN)
    expect(alignToBucket(10 * MIN, 300)).toBe(10 * MIN)
  })
})

describe('aggregateSeries', () => {
  it('sums the series per aligned timestamp and sorts by time', () => {
    const a = [{ t: 0, v: 1 }, { t: MIN, v: 2 }]
    const b = [{ t: 0 + 20_000, v: 10 }, { t: MIN, v: 20 }]
    expect(aggregateSeries([a, b], 60)).toEqual([{ t: 0, v: 11 }, { t: MIN, v: 22 }])
  })
  it('handles series that start or end at different times', () => {
    const a = [{ t: 0, v: 1 }, { t: MIN, v: 1 }]
    const b = [{ t: MIN, v: 5 }, { t: 2 * MIN, v: 5 }]
    expect(aggregateSeries([a, b], 60)).toEqual([{ t: 0, v: 1 }, { t: MIN, v: 6 }, { t: 2 * MIN, v: 5 }])
  })
  it('ignores null values and returns [] for no series', () => {
    expect(aggregateSeries([[{ t: 0, v: null }, { t: MIN, v: 3 }]], 60)).toEqual([{ t: MIN, v: 3 }])
    expect(aggregateSeries([], 60)).toEqual([])
  })
})

describe('splitSegments', () => {
  it('splits at null values and at gaps longer than 1.5 buckets', () => {
    const pts = [
      { t: 0, v: 1 },
      { t: MIN, v: 2 },
      { t: 2 * MIN, v: null },
      { t: 3 * MIN, v: 4 },
      { t: 10 * MIN, v: 5 },
    ]
    expect(splitSegments(pts, 60)).toEqual([[pts[0], pts[1]], [pts[3]], [pts[4]]])
  })
})

describe('niceTicks', () => {
  it('rounds the maximum up to a clean number with evenly spaced ticks from 0', () => {
    expect(niceTicks(87, 4)).toEqual({ ticks: [0, 25, 50, 75, 100], max: 100 })
    expect(niceTicks(3.2, 4)).toEqual({ ticks: [0, 1, 2, 3, 4], max: 4 })
  })
  it('copes with an all-zero series', () => {
    const { ticks, max } = niceTicks(0, 4)
    expect(max).toBeGreaterThan(0)
    expect(ticks[0]).toBe(0)
  })
})

describe('timeTicks', () => {
  it('returns hourly ticks inside a 6h window, each aligned to a full hour', () => {
    const from = new Date(2026, 9, 25, 8, 20).getTime()
    const to = from + 6 * 60 * MIN
    const ticks = timeTicks(from, to, '6h')
    expect(ticks.length).toBeGreaterThanOrEqual(4)
    expect(ticks.length).toBeLessThanOrEqual(8)
    expect(ticks.every((t) => t >= from && t <= to && new Date(t).getMinutes() === 0)).toBe(true)
  })
  it('returns about one tick per day for 7d, aligned to local midnight', () => {
    const to = new Date(2026, 9, 25, 12).getTime()
    const ticks = timeTicks(to - 7 * 24 * 60 * MIN, to, '7d')
    expect(ticks.length).toBeGreaterThanOrEqual(6)
    expect(ticks.every((t) => new Date(t).getHours() === 0)).toBe(true)
  })
})

describe('formatTick', () => {
  it('shows times for short ranges and dates for long ones', () => {
    const t = new Date(2026, 9, 25, 14, 5).getTime()
    expect(formatTick(t, '1h')).toMatch(/(14|2)[:.]05/)
    expect(formatTick(t, '30d')).not.toMatch(/[:.]05/)
    expect(formatTick(t, '30d')).toMatch(/25/)
  })
})

describe('summarize', () => {
  it('returns min, average and max, skipping nulls', () => {
    expect(summarize([2, null, 4, 9])).toEqual({ min: 2, avg: 5, max: 9 })
  })
  it('returns null when there are no values', () => {
    expect(summarize([null])).toBeNull()
  })
})

describe('nearestIndex', () => {
  it('finds the closest timestamp', () => {
    expect(nearestIndex([0, 100, 200], 140)).toBe(1)
    expect(nearestIndex([0, 100, 200], 160)).toBe(2)
    expect(nearestIndex([0, 100, 200], -50)).toBe(0)
  })
})
