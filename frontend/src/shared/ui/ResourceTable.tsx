import { useMemo, useState, type ReactNode } from 'react'
import './ResourceTable.css'

export interface ResourceColumn<T> {
  id: string
  header: string
  render: (row: T) => ReactNode
  /** Makes the column sortable by this value (strings compare case-insensitively, null sorts last). */
  sortValue?: (row: T) => string | number | null
}

interface Props<T> {
  /** Plural noun, used in labels and messages: "containers". */
  label: string
  columns: ResourceColumn<T>[]
  rows: T[] | undefined
  rowKey: (row: T) => string
  /** Text the search box matches against (case-insensitive substring). */
  searchText: (row: T) => string
  /** Id of the column sorted ascending initially. */
  defaultSort: string
  emptyMessage: string
  isPending?: boolean
  /** Already-mapped error message; shows the error state instead of rows. */
  error?: string | null
  onRetry?: () => void
  /** Extra controls next to the search box (e.g. a filter chip). */
  toolbar?: ReactNode
}

type Sort = { id: string; dir: 'asc' | 'desc' }

function compare(a: string | number | null, b: string | number | null): number {
  if (a === b) return 0
  if (a === null) return 1
  if (b === null) return -1
  if (typeof a === 'number' && typeof b === 'number') return a - b
  return String(a).localeCompare(String(b), undefined, { sensitivity: 'base' })
}

/**
 * Shared list table for environment resources: search, sortable headers, loading skeleton,
 * error state with retry, empty state. Features supply columns and rows only.
 */
export function ResourceTable<T>({
  label,
  columns,
  rows,
  rowKey,
  searchText,
  defaultSort,
  emptyMessage,
  isPending = false,
  error = null,
  onRetry,
  toolbar,
}: Props<T>) {
  const [query, setQuery] = useState('')
  const [sort, setSort] = useState<Sort>({ id: defaultSort, dir: 'asc' })

  const visible = useMemo(() => {
    const needle = query.trim().toLowerCase()
    const filtered = (rows ?? []).filter((r) => !needle || searchText(r).toLowerCase().includes(needle))
    const column = columns.find((c) => c.id === sort.id)
    if (!column?.sortValue) return filtered
    const value = column.sortValue
    const factor = sort.dir === 'asc' ? 1 : -1
    return [...filtered].sort((a, b) => factor * compare(value(a), value(b)))
  }, [rows, query, sort, columns, searchText])

  function toggleSort(id: string) {
    setSort((s) => (s.id === id ? { id, dir: s.dir === 'asc' ? 'desc' : 'asc' } : { id, dir: 'asc' }))
  }

  let body: ReactNode
  if (error) {
    body = (
      <div className="rtable__state">
        <p role="alert">{error}</p>
        {onRetry && (
          <button type="button" className="rtable__btn" onClick={onRetry}>
            Retry
          </button>
        )}
      </div>
    )
  } else if (isPending) {
    body = (
      <div className="rtable__skeleton" role="status" aria-label={`Loading ${label}`} aria-busy="true">
        {[0, 1, 2, 3].map((i) => (
          <span key={i} className="rtable__skeleton-row" />
        ))}
      </div>
    )
  } else if ((rows ?? []).length === 0) {
    body = <p className="rtable__state">{emptyMessage}</p>
  } else if (visible.length === 0) {
    body = <p className="rtable__state">No matches for "{query.trim()}".</p>
  } else {
    body = (
      <div className="rtable__scroll">
        <table className="rtable__table" aria-label={label}>
          <thead>
            <tr>
              {columns.map((c) => {
                const active = sort.id === c.id
                return (
                  <th
                    key={c.id}
                    scope="col"
                    aria-sort={
                      c.sortValue ? (active ? (sort.dir === 'asc' ? 'ascending' : 'descending') : 'none') : undefined
                    }
                  >
                    {c.sortValue ? (
                      <button type="button" className="rtable__sort" onClick={() => toggleSort(c.id)}>
                        {c.header}
                        <span aria-hidden="true" className="rtable__arrow">
                          {active ? (sort.dir === 'asc' ? '↑' : '↓') : ''}
                        </span>
                      </button>
                    ) : (
                      c.header
                    )}
                  </th>
                )
              })}
            </tr>
          </thead>
          <tbody>
            {visible.map((row) => (
              <tr key={rowKey(row)}>
                {columns.map((c) => (
                  <td key={c.id}>{c.render(row)}</td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    )
  }

  return (
    <div className="rtable">
      <div className="rtable__toolbar">
        <input
          type="search"
          className="rtable__search"
          aria-label={`Search ${label}`}
          placeholder={`Search ${label}`}
          value={query}
          onChange={(e) => setQuery(e.target.value)}
        />
        {toolbar}
      </div>
      {body}
    </div>
  )
}

/** Colored status pill; `tone` picks the color, the text always carries the meaning. */
export function Badge({ tone, children }: { tone: 'ok' | 'warn' | 'bad' | 'muted'; children: ReactNode }) {
  return <span className={`rbadge rbadge--${tone}`}>{children}</span>
}
