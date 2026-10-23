import type { EnvEntry } from '../api.ts'

export const ENV_KEY_PATTERN = /^[A-Za-z_][A-Za-z0-9_]*$/

export interface ParsedEnv {
  entries: EnvEntry[]
  /** 1-based line numbers that are neither comment, blank nor `KEY=value`. */
  invalidLines: number[]
}

function unquote(raw: string): string {
  const value = raw.trim()
  if (value.length >= 2 && value.startsWith('"') && value.endsWith('"')) {
    return value
      .slice(1, -1)
      .replace(/\\(["\\nrt])/g, (_, c: string) => ({ '"': '"', '\\': '\\', n: '\n', r: '\r', t: '\t' })[c] ?? c)
  }
  if (value.length >= 2 && value.startsWith("'") && value.endsWith("'")) return value.slice(1, -1)
  // Unquoted: an inline comment starts at " #".
  const hash = value.search(/\s#/)
  return (hash === -1 ? value : value.slice(0, hash)).trimEnd()
}

/** Parses `.env` text: comments, blank lines, `export ` prefix, single/double quotes. Later duplicates are kept as rows. */
export function parseEnv(text: string): ParsedEnv {
  const entries: EnvEntry[] = []
  const invalidLines: number[] = []
  text.split(/\r?\n/).forEach((line, index) => {
    const trimmed = line.trim()
    if (trimmed === '' || trimmed.startsWith('#')) return
    const match = /^(?:export\s+)?([^=\s]+)\s*=(.*)$/.exec(trimmed)
    if (!match) {
      invalidLines.push(index + 1)
      return
    }
    entries.push({ key: match[1], value: unquote(match[2]) })
  })
  return { entries, invalidLines }
}

function quoteIfNeeded(value: string): string {
  if (value === '' || /^[A-Za-z0-9_./:@%+-]+$/.test(value)) return value
  const escaped = value.replace(/\\/g, '\\\\').replace(/"/g, '\\"').replace(/\n/g, '\\n').replace(/\r/g, '\\r').replace(/\t/g, '\\t')
  return `"${escaped}"`
}

/** Inverse of {@link parseEnv} for valid entries (rows without a key are skipped). */
export function serializeEnv(entries: EnvEntry[]): string {
  return entries
    .filter((e) => e.key.trim() !== '')
    .map((e) => `${e.key.trim()}=${quoteIfNeeded(e.value)}`)
    .join('\n')
}

export type EnvIssue = 'INVALID_KEY' | 'DUPLICATE_KEY'

/** Per-row problems (index -> issue). Blank-key rows are ignored (dropped on save). */
export function validateEnv(entries: EnvEntry[]): Map<number, EnvIssue> {
  const issues = new Map<number, EnvIssue>()
  const seen = new Set<string>()
  entries.forEach((e, i) => {
    const key = e.key.trim()
    if (key === '') return
    if (!ENV_KEY_PATTERN.test(key)) issues.set(i, 'INVALID_KEY')
    else if (seen.has(key)) issues.set(i, 'DUPLICATE_KEY')
    seen.add(key)
  })
  return issues
}

/** Merges pasted entries into existing rows: same key overwrites, new keys are appended, empty placeholder rows dropped. */
export function mergeEnv(existing: EnvEntry[], incoming: EnvEntry[]): EnvEntry[] {
  const result = existing.filter((e) => e.key.trim() !== '' || e.value !== '')
  for (const entry of incoming) {
    const at = result.findIndex((e) => e.key.trim() === entry.key)
    if (at === -1) result.push(entry)
    else result[at] = entry
  }
  return result
}

/** Entries to send: trimmed keys, blank-key rows dropped. */
export function toPayload(entries: EnvEntry[]): EnvEntry[] {
  return entries.filter((e) => e.key.trim() !== '').map((e) => ({ key: e.key.trim(), value: e.value }))
}
