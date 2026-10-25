/** Pure data helpers of the usage charts: rates from counters, aggregation, gaps, axis ticks. No React, no DOM. */

export type Range = '1h' | '6h' | '24h' | '7d' | '30d'

/** Spoken form of a range, for chart descriptions. */
export const RANGE_WORDS: Record<Range, string> = { '1h': 'last hour', '6h': 'last 6 hours', '24h': 'last 24 hours', '7d': 'last 7 days', '30d': 'last 30 days' }

export interface TimePoint {
  /** Epoch milliseconds. */
  t: number
  v: number | null
}

const SECOND = 1000
const MINUTE = 60 * SECOND
const HOUR = 60 * MINUTE

/**
 * Per-second rates from cumulative counters, using the real time between samples. The first sample has no
 * predecessor, a decrease means the counter was reset (container restart), and a hole of more than 1.5 buckets
 * (missing samples) cannot be turned into a trustworthy rate; all three are `null` (a gap), never negative.
 */
export function counterRates(samples: { t: number; value: number }[], bucketSeconds: number): (number | null)[] {
  return samples.map((sample, i) => {
    if (i === 0) return null
    const prev = samples[i - 1]
    const seconds = (sample.t - prev.t) / SECOND
    const delta = sample.value - prev.value
    if (delta < 0 || seconds <= 0 || seconds > 1.5 * bucketSeconds) return null
    return delta / seconds
  })
}

/** Floors a timestamp to the start of its bucket. */
export function alignToBucket(ms: number, bucketSeconds: number): number {
  const size = bucketSeconds * SECOND
  return Math.floor(ms / size) * size
}

/** Sum of several series per bucket. A series that starts or ends early just stops contributing; nulls are skipped. */
export function aggregateSeries(series: TimePoint[][], bucketSeconds: number): TimePoint[] {
  const sums = new Map<number, number>()
  for (const points of series) {
    for (const { t, v } of points) {
      if (v === null) continue
      const key = alignToBucket(t, bucketSeconds)
      sums.set(key, (sums.get(key) ?? 0) + v)
    }
  }
  return [...sums.entries()].sort(([a], [b]) => a - b).map(([t, v]) => ({ t, v }))
}

/** Splits a line into drawable runs: a null value or a hole longer than 1.5 buckets ends the run. */
export function splitSegments(points: TimePoint[], bucketSeconds: number): TimePoint[][] {
  const runs: TimePoint[][] = []
  let current: TimePoint[] = []
  let last: TimePoint | null = null
  for (const p of points) {
    if (p.v === null) {
      if (current.length) runs.push(current)
      current = []
      last = null
      continue
    }
    if (last && p.t - last.t > 1.5 * bucketSeconds * SECOND && current.length) {
      runs.push(current)
      current = []
    }
    current.push(p)
    last = p
  }
  if (current.length) runs.push(current)
  return runs
}

/** Clean y-axis: ticks from 0 in steps of 1, 2, 2.5, 5 or 10 times a power of ten, covering `max`. */
export function niceTicks(max: number, count = 4): { ticks: number[]; max: number } {
  const raw = max > 0 ? max / count : 1
  const magnitude = 10 ** Math.floor(Math.log10(raw))
  const normalized = raw / magnitude
  const step = (normalized <= 1 ? 1 : normalized <= 2 ? 2 : normalized <= 2.5 ? 2.5 : normalized <= 5 ? 5 : 10) * magnitude
  const steps = Math.max(1, Math.ceil(max / step - 1e-9))
  const ticks = Array.from({ length: steps + 1 }, (_, i) => Math.round(i * step * 1e6) / 1e6)
  return { ticks, max: ticks[ticks.length - 1] }
}

/** Tick spacing per range; day-sized steps are counted in calendar days so they land on local midnight. */
const TICK_STEP: Record<Range, { ms?: number; days?: number }> = {
  '1h': { ms: 10 * MINUTE },
  '6h': { ms: HOUR },
  '24h': { ms: 4 * HOUR },
  '7d': { days: 1 },
  '30d': { days: 5 },
}

/** Tick timestamps inside [from, to], aligned to round local times. */
export function timeTicks(from: number, to: number, range: Range): number[] {
  const step = TICK_STEP[range]
  const cursor = new Date(from)
  cursor.setHours(0, 0, 0, 0)
  const ticks: number[] = []
  while (cursor.getTime() <= to) {
    if (cursor.getTime() >= from) ticks.push(cursor.getTime())
    if (step.days) cursor.setDate(cursor.getDate() + step.days)
    else cursor.setTime(cursor.getTime() + (step.ms ?? HOUR))
  }
  return ticks
}

const timeFormat = new Intl.DateTimeFormat(undefined, { hour: 'numeric', minute: '2-digit' })
const weekdayFormat = new Intl.DateTimeFormat(undefined, { weekday: 'short', day: 'numeric' })
const dateFormat = new Intl.DateTimeFormat(undefined, { month: 'short', day: 'numeric' })

/** Axis label: clock time for up to a day, weekday and day for a week, date for a month. */
export function formatTick(ms: number, range: Range): string {
  const date = new Date(ms)
  if (range === '7d') return weekdayFormat.format(date)
  if (range === '30d') return dateFormat.format(date)
  return timeFormat.format(date)
}

export function summarize(values: (number | null)[]): { min: number; avg: number; max: number } | null {
  const present = values.filter((v): v is number => v !== null)
  if (present.length === 0) return null
  return { min: Math.min(...present), avg: present.reduce((a, b) => a + b, 0) / present.length, max: Math.max(...present) }
}

/** Index of the timestamp closest to `t` (times sorted ascending, non-empty). */
export function nearestIndex(times: number[], t: number): number {
  let best = 0
  for (let i = 1; i < times.length; i++) {
    if (Math.abs(times[i] - t) < Math.abs(times[best] - t)) best = i
  }
  return best
}
