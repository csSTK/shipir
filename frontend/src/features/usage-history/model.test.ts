import { describe, expect, it } from 'vitest'
import type { HistoryPoint, StatsHistory } from './api.ts'
import { containerCharts, hostCharts } from './model.ts'

const MIN = 60_000
const t0 = Date.UTC(2026, 9, 25, 10, 0)
const point = (i: number, o: Partial<HistoryPoint> = {}): HistoryPoint => ({
  time: new Date(t0 + i * MIN).toISOString(),
  cpuPercent: 10,
  memoryBytes: 100,
  networkRxBytes: 0,
  networkTxBytes: 0,
  blockReadBytes: 0,
  blockWriteBytes: 0,
  ...o,
})
const history = (series: StatsHistory['series']): StatsHistory => ({
  range: '1h',
  from: new Date(t0).toISOString(),
  to: new Date(t0 + 60 * MIN).toISOString(),
  bucketSeconds: 60,
  seriesTotal: series.length,
  truncated: false,
  series,
})

describe('containerCharts', () => {
  it('returns null when there are no samples', () => {
    expect(containerCharts(history([]))).toBeNull()
    expect(containerCharts(history([{ containerId: 'c', points: [] }]))).toBeNull()
  })
  it('turns counters into per-second rates and a restart into a gap', () => {
    const charts = containerCharts(
      history([{ containerId: 'c', points: [point(0, { networkRxBytes: 600 }), point(1, { networkRxBytes: 1200 }), point(2, { networkRxBytes: 60 })] }]),
    )!
    const rx = charts.network.find((s) => s.key === 'rx')!
    expect(rx.points.map((p) => p.v)).toEqual([null, 10, null])
    expect(charts.network.map((s) => s.label)).toEqual(['Received', 'Sent'])
    expect(charts.disk.map((s) => s.label)).toEqual(['Read', 'Written'])
  })
  it('does not inflate the rate after a hole of missing buckets', () => {
    const charts = containerCharts(
      history([{ containerId: 'c', points: [point(0, { networkRxBytes: 0 }), point(10, { networkRxBytes: 600 }), point(11, { networkRxBytes: 660 })] }]),
    )!
    expect(charts.network[0].points.map((p) => p.v)).toEqual([null, null, 1])
  })
  it('keeps cpu and memory values as they are, timed in epoch milliseconds', () => {
    const charts = containerCharts(history([{ containerId: 'c', points: [point(0, { cpuPercent: 12.5, memoryBytes: 2048 })] }]))!
    expect(charts.cpu[0].points).toEqual([{ t: t0, v: 12.5 }])
    expect(charts.memory[0].points).toEqual([{ t: t0, v: 2048 }])
  })
})

describe('hostCharts', () => {
  it('sums cpu and memory of all containers per timestamp', () => {
    const charts = hostCharts(
      history([
        { containerId: 'a', points: [point(0, { cpuPercent: 10, memoryBytes: 100 }), point(1, { cpuPercent: 20, memoryBytes: 100 })] },
        { containerId: 'b', points: [point(1, { cpuPercent: 5, memoryBytes: 50 })] },
      ]),
    )!
    expect(charts.cpu[0].points).toEqual([{ t: t0, v: 10 }, { t: t0 + MIN, v: 25 }])
    expect(charts.memory[0].points).toEqual([{ t: t0, v: 100 }, { t: t0 + MIN, v: 150 }])
  })
  it('returns null without samples', () => {
    expect(hostCharts(history([]))).toBeNull()
  })
})
