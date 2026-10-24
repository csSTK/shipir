import './Sparkline.css'

interface SparklineProps {
  values: number[]
  /** Fixed upper bound of the scale (e.g. 100 for percentages); defaults to the largest value. */
  max?: number
  width?: number
  height?: number
  /** Accessible description; the line itself is decorative. */
  label: string
}

/** Tiny dependency-free SVG line chart without axes or animation; the caller shows the number next to it. */
export function Sparkline({ values, max, width = 80, height = 24, label }: SparklineProps) {
  const top = Math.max(max ?? 0, ...values, 1e-9)
  const step = values.length > 1 ? width / (values.length - 1) : 0
  const pad = 2
  const points = values
    .map((v, i) => `${(i * step).toFixed(1)},${(height - pad - (Math.max(v, 0) / top) * (height - 2 * pad)).toFixed(1)}`)
    .join(' ')
  return (
    <svg className="sparkline" width={width} height={height} viewBox={`0 0 ${width} ${height}`} role="img" aria-label={label}>
      {values.length > 1 && <polyline points={points} fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinejoin="round" strokeLinecap="round" />}
    </svg>
  )
}
