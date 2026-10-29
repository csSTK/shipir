export interface MatchRange {
  start: number
  end: number
}

/** All non-overlapping case-insensitive occurrences of `query` in `text` (plain substring, no regex). */
export function findMatches(text: string, query: string): MatchRange[] {
  if (!query) return []
  const haystack = text.toLowerCase()
  const needle = query.toLowerCase()
  // Lower-casing can change the length for a few characters; then offsets would not map back, so skip those lines.
  if (haystack.length !== text.length) return []
  const ranges: MatchRange[] = []
  for (let from = haystack.indexOf(needle); from !== -1; from = haystack.indexOf(needle, from + needle.length)) {
    ranges.push({ start: from, end: from + needle.length })
  }
  return ranges
}

export interface SearchResult {
  /** Matches per line seq; lines without a match are absent. */
  ranges: Map<number, MatchRange[]>
  /** Lines with matches in display order, with their match count. */
  matched: { seq: number; count: number }[]
  total: number
}

/**
 * Remembers each line's matches for the current query, so a changed buffer only searches the lines it has not seen.
 * Lines that left the buffer are forgotten on the next call.
 */
export class MatchCache {
  private query = ''
  private bySeq = new Map<number, MatchRange[]>()

  private readonly find: (text: string, query: string) => MatchRange[]

  constructor(find: (text: string, query: string) => MatchRange[] = findMatches) {
    this.find = find
  }

  search(lines: readonly { seq: number; text: string }[], query: string): SearchResult {
    const ranges = new Map<number, MatchRange[]>()
    const matched: { seq: number; count: number }[] = []
    let total = 0
    if (query !== this.query) {
      this.query = query
      this.bySeq = new Map()
    }
    if (!query) return { ranges, matched, total }
    const next = new Map<number, MatchRange[]>()
    for (const l of lines) {
      const r = this.bySeq.get(l.seq) ?? this.find(l.text, query)
      next.set(l.seq, r)
      if (r.length) {
        ranges.set(l.seq, r)
        matched.push({ seq: l.seq, count: r.length })
        total += r.length
      }
    }
    this.bySeq = next
    return { ranges, matched, total }
  }
}
