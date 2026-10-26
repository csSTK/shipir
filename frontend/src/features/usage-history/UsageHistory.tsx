import { useMemo, type ReactNode } from 'react'
import { useSearchParams } from 'react-router-dom'
import { ApiError } from '../../shared/api/client.ts'
import { TimeSeriesChart, type ChartSeries } from '../../shared/charts/TimeSeriesChart.tsx'
import type { Range } from '../../shared/charts/series.ts'
import { Button, Select, Skeleton } from '../../shared/ui/index.ts'
import { formatSize } from '../hosts/format.ts'
import { parseRange, RANGES, useStatsHistory, type StatsHistory } from './api.ts'
import { containerCharts, hostCharts } from './model.ts'
import { useTranslation } from 'react-i18next'
import { formatNumber } from '../../shared/i18n/format.ts'
import './UsageHistory.css'

const percent = (v: number) => {
  const digits = v > 0 && v < 10 ? 1 : 0
  return `${formatNumber(v, { minimumFractionDigits: digits, maximumFractionDigits: digits })}%`
}
const rate = (v: number) => `${formatSize(Math.round(v))}/s`

/** Range kept in the URL so a view can be shared and survives reload. */
function useRangeParam(param: string): [Range, (r: Range) => void] {
  const [params, setParams] = useSearchParams()
  const range = parseRange(params.get(param))
  const set = (r: Range) =>
    setParams(
      (prev) => {
        const next = new URLSearchParams(prev)
        next.set(param, r)
        return next
      },
      { replace: true },
    )
  return [range, set]
}

function RangeSelect({ value, onChange }: { value: Range; onChange: (r: Range) => void }) {
  const { t } = useTranslation('usage-history')
  return (
    <div className="usage__range">
      <Select label={t('UsageHistory.timeRange')} hideLabel value={value} onChange={(e) => onChange(e.target.value as Range)}>
        {RANGES.map((r) => (
          <option key={r.value} value={r.value}>
            {r.label}
          </option>
        ))}
      </Select>
    </div>
  )
}

interface FrameProps {
  query: ReturnType<typeof useStatsHistory>
  range: Range
  onRange: (r: Range) => void
  /** Rendered once data with samples is there. */
  children: (history: StatsHistory) => ReactNode
  hasData: (history: StatsHistory) => boolean
}

/** Range selector plus the loading, error and empty states shared by both views. */
function Frame({ query, range, onRange, children, hasData }: FrameProps) {
  const { t } = useTranslation('usage-history')
  const { data, isPending, isError, error, refetch, isFetching } = query
  return (
    <div className="usage">
      <RangeSelect value={range} onChange={onRange} />
      {isPending && (
        <div className="usage__grid" role="status" aria-label={t('UsageHistory.loadingUsageHistory')} aria-busy="true">
          <Skeleton height="10.5rem" />
          <Skeleton height="10.5rem" />
        </div>
      )}
      {isError && !data && (
        <div role="alert" className="ui-error">
          <p>{t('UsageHistory.couldNotLoadUsageHistory')}</p>
          {!(error instanceof ApiError && error.status === 404) && (
            <Button variant="ghost" size="sm" onClick={() => void refetch()}>
              {t('UsageHistory.retry')}
            </Button>
          )}
        </div>
      )}
      {data && !hasData(data) && <p className="usage__empty">{t('UsageHistory.noHistoryYetSamplesAre')}</p>}
      {data && hasData(data) && (
        // The previous render stays (dimmed) while a new range loads, so the layout does not jump.
        <div className={isFetching ? 'usage__body usage__body--busy' : 'usage__body'}>{children(data)}</div>
      )}
    </div>
  )
}

function Chart({ title, series, history, format, max, binary }: { title: string; series: ChartSeries[]; history: StatsHistory; format: (v: number) => string; max?: number; binary?: boolean }) {
  return (
    <TimeSeriesChart
      title={title}
      series={series}
      from={Date.parse(history.from)}
      to={Date.parse(history.to)}
      range={history.range}
      bucketSeconds={history.bucketSeconds}
      format={format}
      max={max}
      binary={binary}
    />
  )
}

function ContainerCharts({ history }: { history: StatsHistory }) {
  const { t } = useTranslation('usage-history')
  const charts = useMemo(() => containerCharts(history), [history])
  if (!charts) return null
  // A multi-threaded container can use more than 100 % (several cores).
  const cpuMax = Math.max(100, ...charts.cpu[0].points.map((p) => p.v ?? 0))
  return (
    <div className="usage__grid">
      <Chart title={t('UsageHistory.cpu')} series={charts.cpu} history={history} format={percent} max={cpuMax} />
      <Chart title={t('UsageHistory.memory')} series={charts.memory} history={history} format={formatSize} binary />
      <Chart title={t('UsageHistory.network')} series={charts.network} history={history} format={rate} binary />
      <Chart title={t('UsageHistory.disk')} series={charts.disk} history={history} format={rate} binary />
    </div>
  )
}

/** Usage history of one container: CPU, memory, network and disk rates. */
export function ContainerUsageHistory({ hostId, containerId }: { hostId: string; containerId: string }) {
  const [range, setRange] = useRangeParam('usageRange')
  const query = useStatsHistory(hostId, range, containerId)
  return (
    <Frame query={query} range={range} onRange={setRange} hasData={(h) => containerCharts(h) !== null}>
      {(h) => <ContainerCharts history={h} />}
    </Frame>
  )
}

function HostCharts({ history }: { history: StatsHistory }) {
  const { t } = useTranslation('usage-history')
  const charts = useMemo(() => hostCharts(history), [history])
  if (!charts) return null
  // The sum of several containers can pass 100 % on a multi-core host.
  const cpuMax = Math.max(100, ...charts.cpu[0].points.map((p) => p.v ?? 0))
  return (
    <>
      <div className="usage__grid">
        <Chart title={t('UsageHistory.cpuAllContainers')} series={charts.cpu} history={history} format={percent} max={cpuMax} />
        <Chart title={t('UsageHistory.memoryAllContainers')} series={charts.memory} history={history} format={formatSize} binary />
      </div>
      {history.truncated && <p className="usage__note">{t('UsageHistory.showingTheLengthMostRecently', { count: history.series.length })}</p>}
    </>
  )
}

/**
 * Aggregate CPU and memory of the containers of one host. Renders nothing when the host is not visible to the user
 * (404), so roles without access see no empty card.
 */
export function HostUsageHistory({ hostId }: { hostId: string }) {
  const { t } = useTranslation('usage-history')
  const [range, setRange] = useRangeParam('usageRange')
  const query = useStatsHistory(hostId, range)
  if (query.error instanceof ApiError && query.error.status === 404) return null
  return (
    <section className="usage-card" aria-labelledby="usage-history-title">
      <h2 id="usage-history-title" className="usage__heading">
        {t('UsageHistory.usageHistory')}
      </h2>
      <Frame query={query} range={range} onRange={setRange} hasData={(h) => hostCharts(h) !== null}>
        {(h) => <HostCharts history={h} />}
      </Frame>
    </section>
  )
}
