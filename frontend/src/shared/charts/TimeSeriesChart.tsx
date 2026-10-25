import { useEffect, useId, useMemo, useRef, useState, type KeyboardEvent, type PointerEvent } from 'react'
import { RANGE_WORDS, formatTick, nearestIndex, niceTicks, splitSegments, summarize, timeTicks, type Range, type TimePoint } from './series.ts'
import './TimeSeriesChart.css'

export interface ChartSeries {
  key: string
  label: string
  points: TimePoint[]
}

export interface TimeSeriesChartProps {
  title: string
  series: ChartSeries[]
  /** Window of the x axis, epoch milliseconds. */
  from: number
  to: number
  range: Range
  bucketSeconds: number
  /** Formats a value for tooltip, axis and summary. */
  format: (value: number) => string
  /** Fixed upper bound of the y axis (e.g. 100 for percentages). */
  max?: number
  /** Value is a byte count: y ticks fall on powers of 1024 instead of powers of ten. */
  binary?: boolean
}

const HEIGHT = 168
const FALLBACK_WIDTH = 560
const MARGIN = { top: 10, right: 12, bottom: 24, left: 76 }
const SERIES_VARS = ['--chart-1', '--chart-2']
const tooltipTime = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' })

/** Width of an element, kept in sync by a ResizeObserver (a fallback where there is none, e.g. jsdom). */
function useWidth(ref: React.RefObject<HTMLElement | null>): number {
  const [width, setWidth] = useState(FALLBACK_WIDTH)
  useEffect(() => {
    const el = ref.current
    if (!el || typeof ResizeObserver === 'undefined') return
    const observer = new ResizeObserver(([entry]) => {
      if (entry.contentRect.width > 0) setWidth(entry.contentRect.width)
    })
    observer.observe(el)
    return () => observer.disconnect()
  }, [ref])
  return width
}

/** Dependency-free SVG line chart: shared time axis, crosshair tooltip on hover and arrow keys, gaps for missing data. */
export function TimeSeriesChart({ title, series, from, to, range, bucketSeconds, format, max, binary = false }: TimeSeriesChartProps) {
  const wrapRef = useRef<HTMLDivElement>(null)
  const width = useWidth(wrapRef)
  const [cursor, setCursor] = useState<number | null>(null)
  const descId = useId()

  const plotW = Math.max(width - MARGIN.left - MARGIN.right, 1)
  const plotH = HEIGHT - MARGIN.top - MARGIN.bottom

  const times = useMemo(() => [...new Set(series.flatMap((s) => s.points.map((p) => p.t)))].sort((a, b) => a - b), [series])
  const allValues = series.flatMap((s) => s.points.map((p) => p.v))
  const summary = summarize(allValues)

  const y = useMemo(() => {
    const top = max ?? Math.max(0, ...allValues.map((v) => v ?? 0))
    if (!binary) return niceTicks(top)
    const unit = 1024 ** Math.max(0, Math.floor(Math.log(Math.max(top, 1)) / Math.log(1024)))
    const { ticks, max: niceMax } = niceTicks(top / unit)
    return { ticks: ticks.map((t) => t * unit), max: niceMax * unit }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [series, max, binary])

  const x = (t: number) => MARGIN.left + ((t - from) / Math.max(to - from, 1)) * plotW
  const yOf = (v: number) => MARGIN.top + plotH - (Math.min(Math.max(v, 0), y.max) / y.max) * plotH
  const color = (i: number) => `var(${SERIES_VARS[i % SERIES_VARS.length]})`

  const label = summary
    ? `${title}, ${RANGE_WORDS[range]}: min ${format(summary.min)}, avg ${format(summary.avg)}, max ${format(summary.max)}`
    : `${title}: no data`

  const cursorTime = cursor === null ? null : times[cursor]

  function onKeyDown(e: KeyboardEvent<SVGSVGElement>) {
    if (times.length === 0) return
    const last = times.length - 1
    const move = (next: number) => {
      e.preventDefault()
      setCursor(Math.min(Math.max(next, 0), last))
    }
    if (e.key === 'ArrowRight') move(cursor === null ? 0 : cursor + 1)
    else if (e.key === 'ArrowLeft') move(cursor === null ? last : cursor - 1)
    else if (e.key === 'Home') move(0)
    else if (e.key === 'End') move(last)
    else if (e.key === 'Escape') setCursor(null)
  }

  function onPointerMove(e: PointerEvent<SVGSVGElement>) {
    if (times.length === 0) return
    const rect = e.currentTarget.getBoundingClientRect()
    const px = ((e.clientX - rect.left) / Math.max(rect.width, 1)) * width
    const t = from + ((px - MARGIN.left) / plotW) * (to - from)
    setCursor(nearestIndex(times, t))
  }

  const tooltipLeft = cursorTime === null ? 0 : x(cursorTime)
  return (
    <figure className="tschart" ref={wrapRef}>
      <figcaption className="tschart__title">{title}</figcaption>
      <svg
        className="tschart__svg"
        width={width}
        height={HEIGHT}
        viewBox={`0 0 ${width} ${HEIGHT}`}
        role="img"
        aria-label={label}
        aria-describedby={descId}
        tabIndex={0}
        onKeyDown={onKeyDown}
        onPointerMove={onPointerMove}
        onPointerLeave={() => setCursor(null)}
        onBlur={() => setCursor(null)}
      >
        {y.ticks.map((tick) => (
          <g key={tick}>
            <line className="tschart__grid" x1={MARGIN.left} x2={width - MARGIN.right} y1={yOf(tick)} y2={yOf(tick)} />
            <text className="tschart__label" x={MARGIN.left - 8} y={yOf(tick)} textAnchor="end" dominantBaseline="middle">
              {format(tick)}
            </text>
          </g>
        ))}
        {timeTicks(from, to, range).map((t) => (
          <text key={t} className="tschart__label" x={x(t)} y={HEIGHT - 6} textAnchor="middle">
            {formatTick(t, range)}
          </text>
        ))}
        {series.map((s, i) => {
          const segments = splitSegments(s.points, bucketSeconds)
          const lastSegment = segments[segments.length - 1]
          const end = lastSegment?.[lastSegment.length - 1]
          return (
            <g key={s.key} style={{ color: color(i) }}>
              {segments.map((seg, n) => {
                const d = seg.map((p, k) => `${k === 0 ? 'M' : 'L'}${x(p.t).toFixed(1)},${yOf(p.v ?? 0).toFixed(1)}`).join('')
                return (
                  <g key={n}>
                    {series.length === 1 && seg.length > 1 && (
                      <path className="tschart__area" d={`${d}L${x(seg[seg.length - 1].t).toFixed(1)},${yOf(0)}L${x(seg[0].t).toFixed(1)},${yOf(0)}Z`} />
                    )}
                    <path className="tschart__line" d={d} />
                    {seg.length === 1 && <circle className="tschart__dot" cx={x(seg[0].t)} cy={yOf(seg[0].v ?? 0)} r={4} />}
                  </g>
                )
              })}
              {end && segments.some((seg) => seg.length > 1) && <circle className="tschart__dot" cx={x(end.t)} cy={yOf(end.v ?? 0)} r={4} />}
            </g>
          )
        })}
        {cursorTime !== null && (
          <g>
            <line className="tschart__cursor" x1={x(cursorTime)} x2={x(cursorTime)} y1={MARGIN.top} y2={MARGIN.top + plotH} />
            {series.map((s, i) => {
              const p = s.points.find((q) => q.t === cursorTime)
              return p && p.v !== null ? (
                <circle key={s.key} className="tschart__dot" style={{ color: color(i) }} cx={x(cursorTime)} cy={yOf(p.v)} r={4} />
              ) : null
            })}
          </g>
        )}
      </svg>
      <span id={descId} hidden>
        Use the left and right arrow keys to read values, Escape to leave.
      </span>
      <div
        role="status"
        className={['tschart__tip', cursorTime !== null && tooltipLeft > width / 2 && 'tschart__tip--left'].filter(Boolean).join(' ')}
        style={cursorTime === null ? undefined : { left: tooltipLeft }}
      >
        {cursorTime !== null && (
          <>
            <span className="tschart__tip-time">{tooltipTime.format(cursorTime)}</span>
            {series.map((s, i) => {
              const v = s.points.find((q) => q.t === cursorTime)?.v ?? null
              return (
                <span key={s.key} className="tschart__tip-row">
                  <span className="tschart__key" style={{ background: color(i) }} aria-hidden="true" />
                  <strong>{v === null ? 'No data' : format(v)}</strong>
                  <span className="tschart__tip-label">{s.label}</span>
                </span>
              )
            })}
          </>
        )}
      </div>
      {series.length > 1 && (
        <ul className="tschart__legend" aria-label="Legend">
          {series.map((s, i) => (
            <li key={s.key}>
              <span className="tschart__key" style={{ background: color(i) }} aria-hidden="true" />
              {s.label}
            </li>
          ))}
        </ul>
      )}
    </figure>
  )
}
