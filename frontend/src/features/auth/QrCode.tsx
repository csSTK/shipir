import { create } from 'qrcode'

/**
 * QR code rendered as one SVG path from the `qrcode` library's module matrix (generated locally,
 * nothing leaves the browser). Black on white regardless of theme: scanners need that contrast.
 */
export function QrCode({ value, label }: { value: string; label: string }) {
  const { modules } = create(value, { errorCorrectionLevel: 'M' })
  const quiet = 4
  const size = modules.size + quiet * 2
  let path = ''
  for (let row = 0; row < modules.size; row++) {
    for (let col = 0; col < modules.size; col++) {
      if (modules.get(row, col)) path += `M${col + quiet} ${row + quiet}h1v1h-1z`
    }
  }
  return (
    <svg className="qr" viewBox={`0 0 ${size} ${size}`} role="img" aria-label={label} shapeRendering="crispEdges">
      <rect width={size} height={size} fill="white" />
      <path d={path} fill="black" />
    </svg>
  )
}
