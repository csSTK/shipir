import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { TimeSeriesChart, type ChartSeries } from './TimeSeriesChart.tsx'

afterEach(cleanup)

const MIN = 60_000
const t0 = new Date(2026, 9, 25, 10, 0).getTime()
const pts = (values: (number | null)[]) => values.map((v, i) => ({ t: t0 + i * MIN, v }))
const cpu: ChartSeries = { key: 'cpu', label: 'CPU', points: pts([10, 20, 60]) }
const fmt = (v: number) => `${v.toFixed(1)}%`

function chart(series: ChartSeries[] = [cpu]) {
  render(<TimeSeriesChart title="CPU" series={series} from={t0} to={t0 + 2 * MIN} range="1h" bucketSeconds={60} format={fmt} max={100} />)
}

describe('TimeSeriesChart', () => {
  it('describes the chart for screen readers with min, average and max', () => {
    chart()
    const img = screen.getByRole('img', { name: /CPU/ })
    const label = img.getAttribute('aria-label')!
    expect(label).toContain('min 10.0%')
    expect(label).toContain('avg 30.0%')
    expect(label).toContain('max 60.0%')
  })

  it('shows a legend only for two or more series', () => {
    chart()
    expect(screen.queryByRole('list', { name: 'Legend' })).not.toBeInTheDocument()
    cleanup()
    chart([cpu, { key: 'b', label: 'Other', points: pts([1, 2, 3]) }])
    expect(screen.getByRole('list', { name: 'Legend' })).toHaveTextContent('Other')
  })

  it('moves the cursor with the arrow keys and announces the exact values', () => {
    chart()
    const img = screen.getByRole('img', { name: /CPU/ })
    img.focus()
    fireEvent.keyDown(img, { key: 'End' })
    expect(screen.getByRole('status')).toHaveTextContent('60.0%')
    fireEvent.keyDown(img, { key: 'ArrowLeft' })
    expect(screen.getByRole('status')).toHaveTextContent('20.0%')
    fireEvent.keyDown(img, { key: 'Escape' })
    expect(screen.getByRole('status')).toBeEmptyDOMElement()
  })

  it('says so in the tooltip when a series has no value at the cursor (gap)', () => {
    chart([{ key: 'cpu', label: 'CPU', points: pts([10, null, 60]) }])
    const img = screen.getByRole('img', { name: /CPU/ })
    img.focus()
    fireEvent.keyDown(img, { key: 'ArrowRight' })
    fireEvent.keyDown(img, { key: 'ArrowRight' })
    expect(screen.getByRole('status')).toHaveTextContent(/no data/i)
  })
})
