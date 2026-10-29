import { Fragment } from 'react'
import type { MatchRange } from './logSearch.ts'

/** One log line with its search matches wrapped in <mark>; `active` is the index of the active match inside this line, or -1. */
export function Highlighted({ text, ranges, active }: { text: string; ranges?: MatchRange[]; active: number }) {
  if (!ranges) return <>{text || ' '}</>
  const parts: React.ReactNode[] = []
  let at = 0
  ranges.forEach((r, i) => {
    if (r.start > at) parts.push(text.slice(at, r.start))
    parts.push(
      <mark key={i} className="logv__mark" data-active={i === active ? 'true' : undefined}>
        {text.slice(r.start, r.end)}
      </mark>,
    )
    at = r.end
  })
  if (at < text.length) parts.push(text.slice(at))
  return <Fragment>{parts}</Fragment>
}
