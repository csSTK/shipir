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
