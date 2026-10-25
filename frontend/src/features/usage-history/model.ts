import type { ChartSeries } from '../../shared/charts/TimeSeriesChart.tsx'
import { aggregateSeries, counterRates } from '../../shared/charts/series.ts'
import type { HistoryPoint, StatsHistory } from './api.ts'


export interface ContainerCharts {
  cpu: ChartSeries[]
  memory: ChartSeries[]
  network: ChartSeries[]
  disk: ChartSeries[]
}

const time = (p: HistoryPoint) => Date.parse(p.time)

function rateSeries(points: HistoryPoint[], key: string, label: string, pick: (p: HistoryPoint) => number, bucketSeconds: number): ChartSeries {
  const rates = counterRates(points.map((p) => ({ t: time(p), value: pick(p) })), bucketSeconds)
  return { key, label, points: points.map((p, i) => ({ t: time(p), v: rates[i] })) }
}

/** Chart series of one container. Counters become per-second rates; a restart (counter decrease) is a gap. */
export function containerCharts(history: StatsHistory): ContainerCharts | null {
  const points = history.series[0]?.points ?? []
  if (points.length === 0) return null
  const b = history.bucketSeconds
  return {
    cpu: [{ key: 'cpu', label: 'CPU', points: points.map((p) => ({ t: time(p), v: p.cpuPercent })) }],
    memory: [{ key: 'memory', label: 'Memory', points: points.map((p) => ({ t: time(p), v: p.memoryBytes })) }],
    network: [
      rateSeries(points, 'rx', 'Received', (p) => p.networkRxBytes, b),
      rateSeries(points, 'tx', 'Sent', (p) => p.networkTxBytes, b),
    ],
    disk: [
      rateSeries(points, 'read', 'Read', (p) => p.blockReadBytes, b),
      rateSeries(points, 'write', 'Written', (p) => p.blockWriteBytes, b),
    ],
  }
}

/** Host-wide CPU and memory: the series of all returned containers summed per bucket. */
export function hostCharts(history: StatsHistory): { cpu: ChartSeries[]; memory: ChartSeries[] } | null {
  if (history.series.every((s) => s.points.length === 0)) return null
  const sum = (pick: (p: HistoryPoint) => number) =>
    aggregateSeries(
      history.series.map((s) => s.points.map((p) => ({ t: time(p), v: pick(p) }))),
      history.bucketSeconds,
    )
  return {
    cpu: [{ key: 'cpu', label: 'CPU', points: sum((p) => p.cpuPercent) }],
    memory: [{ key: 'memory', label: 'Memory', points: sum((p) => p.memoryBytes) }],
  }
}
