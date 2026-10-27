const FALLBACK_PX = 13

/** Monaco wants pixels: resolve the --text-small-size token (rem) against the root font size. */
export function editorFontSize(): number {
  if (typeof document === 'undefined') return FALLBACK_PX
  const root = getComputedStyle(document.documentElement)
  const rem = parseFloat(root.getPropertyValue('--text-small-size'))
  const rootPx = parseFloat(root.fontSize)
  return Number.isFinite(rem) && Number.isFinite(rootPx) ? Math.round(rem * rootPx) : FALLBACK_PX
}
