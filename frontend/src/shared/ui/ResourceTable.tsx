import {
  type ColumnDef,
  type ColumnSizingState,
  type SortingState,
  type VisibilityState,
  flexRender,
  getCoreRowModel,
  getSortedRowModel,
  useReactTable,
} from '@tanstack/react-table'
import { ArrowDown, ArrowUp, Check, Columns3, Ellipsis, Rows3, Rows4, type LucideIcon } from 'lucide-react'
import { useCallback, useEffect, useMemo, useRef, useState, type KeyboardEvent, type PointerEvent, type ReactNode } from 'react'
import { Button } from './Button.tsx'
import { EmptyState } from './EmptyState.tsx'
import { IconButton } from './IconButton.tsx'
import { Menu, type MenuEntry } from './Menu.tsx'
import { SearchField } from './SearchField.tsx'
import { Skeleton } from './Skeleton.tsx'
import { useTranslation } from 'react-i18next'
import { useMediaQuery } from '../lib/useMediaQuery.ts'
import './ResourceTable.css'
import { PHONE_QUERY } from '../lib/breakpoints.ts'

export interface ResourceColumn<T> {
  id: string
  header: string
  render: (row: T) => ReactNode
  /** Makes the column sortable by this value (strings compare case-insensitively, null sorts last). */
  sortValue?: (row: T) => string | number | null
  /** Initial width in px (default 160). */
  defaultWidth?: number
  /** Smallest width in px the column can be resized to (default 64). */
  minWidth?: number
  /** Set false to keep the column out of the visibility menu (always shown). */
  hideable?: boolean
  /** Hidden initially until the user turns it on. */
  defaultHidden?: boolean
  /**
   * Primary text column: takes the free width of the table instead of a fixed one (until the user
   * resizes it), so names and images are not cut off while space is left over.
   */
  flex?: boolean
  /** True when the row has no value here. A column empty for every row is hidden unless the user turns it on. */
  isEmpty?: (row: T) => boolean
}

/** On phones the first column is capped so the next one (status) still fits before the pinned actions. */
const NARROW_PRIMARY_WIDTH = 112

/** One icon button in the pinned actions column. */
export interface RowAction {
  label: string
  icon: LucideIcon
  onSelect: () => void
  disabled?: boolean
  danger?: boolean
}

export interface RowActions {
  /** Shown as icon buttons. */
  primary?: RowAction[]
  /** Shown in the overflow menu. */
  menu?: MenuEntry[]
}

interface Props<T> {
  /** Plural noun, used in labels and messages: "containers". */
  label: string
  /** Key for the saved column widths, hidden columns and density (default: `label`). */
  id?: string
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
  /** Actions per row, pinned to the right edge of the table. */
  rowActions?: (row: T) => RowActions
  /** Names a row for the accessible labels of its actions. */
  rowLabel?: (row: T) => string
}

type Density = 'comfortable' | 'compact'
interface Prefs {
  widths: ColumnSizingState
  hidden: string[]
  density: Density
}

const DEFAULT_WIDTH = 160
const MIN_WIDTH = 64
const MAX_WIDTH = 900
const KEY_STEP = 16
const KEY_STEP_LARGE = 64

function storageKey(id: string) {
  return `shipir.table.${id}`
}

function loadPrefs(id: string): Partial<Prefs> {
  try {
    const raw = window.localStorage.getItem(storageKey(id))
    if (!raw) return {}
    const parsed = JSON.parse(raw) as Partial<Prefs>
    const out: Partial<Prefs> = {}
    if (parsed.widths && typeof parsed.widths === 'object') {
      out.widths = Object.fromEntries(Object.entries(parsed.widths).filter(([, v]) => typeof v === 'number' && Number.isFinite(v)))
    }
    if (Array.isArray(parsed.hidden)) out.hidden = parsed.hidden.filter((h): h is string => typeof h === 'string')
    if (parsed.density === 'compact' || parsed.density === 'comfortable') out.density = parsed.density
    return out
  } catch {
    return {}
  }
}

function savePrefs(id: string, prefs: Prefs) {
  try {
    window.localStorage.setItem(storageKey(id), JSON.stringify(prefs))
  } catch {
    // storage unavailable or full: preferences just don't persist
  }
}

function compare(a: unknown, b: unknown): number {
  if (a === b) return 0
  if (a === null || a === undefined) return 1
  if (b === null || b === undefined) return -1
  if (typeof a === 'number' && typeof b === 'number') return a - b
  return String(a).localeCompare(String(b), undefined, { sensitivity: 'base' })
}

/**
 * Shared list table for environment resources, built on TanStack Table: search, sortable headers,
 * resizable columns (drag or arrow keys, double-click fits the content), column visibility, density,
 * a pinned actions column and loading/error/empty states. Widths, hidden columns and density are
 * remembered per table `id` in localStorage.
 */
export function ResourceTable<T>({
  label,
  id,
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
  rowActions,
  rowLabel,
}: Props<T>) {
  const { t } = useTranslation('common')
  const tableId = id ?? label
  const [query, setQuery] = useState('')
  const [sorting, setSorting] = useState<SortingState>([{ id: defaultSort, desc: false }])
  const [initial] = useState(() => loadPrefs(tableId))
  const [sizing, setSizing] = useState<ColumnSizingState>(initial.widths ?? {})
  const [visibility, setVisibility] = useState<VisibilityState>(() => {
    const hidden = initial.hidden ?? columns.filter((c) => c.defaultHidden).map((c) => c.id)
    return Object.fromEntries(hidden.map((h) => [h, false]))
  })
  const [density, setDensity] = useState<Density>(initial.density ?? 'comfortable')
  const tableRef = useRef<HTMLTableElement>(null)

  useEffect(() => {
    const hidden = Object.entries(visibility)
      .filter(([, v]) => v === false)
      .map(([k]) => k)
    savePrefs(tableId, { widths: sizing, hidden, density })
  }, [tableId, sizing, visibility, density])

  const filtered = useMemo(() => {
    const needle = query.trim().toLowerCase()
    return (rows ?? []).filter((r) => !needle || searchText(r).toLowerCase().includes(needle))
  }, [rows, query, searchText])

  const columnDefs = useMemo<ColumnDef<T>[]>(
    () =>
      columns.map((c) => ({
        id: c.id,
        header: c.header,
        accessorFn: (row: T) => (c.sortValue ? c.sortValue(row) : null),
        cell: (info) => c.render(info.row.original),
        enableSorting: !!c.sortValue,
        sortDescFirst: false,
        enableHiding: c.hideable !== false,
        sortingFn: (a, b, columnId) => compare(a.getValue(columnId), b.getValue(columnId)),
        size: c.defaultWidth ?? DEFAULT_WIDTH,
        minSize: c.minWidth ?? MIN_WIDTH,
        maxSize: MAX_WIDTH,
      })),
    [columns],
  )

  // columns without a value in any row stay out of the way until the user turns them on
  const effectiveVisibility = useMemo<VisibilityState>(() => {
    const all = rows ?? []
    if (all.length === 0) return visibility
    const out = { ...visibility }
    for (const c of columns) {
      if (c.isEmpty && out[c.id] === undefined && all.every(c.isEmpty)) out[c.id] = false
    }
    return out
  }, [rows, columns, visibility])

  const table = useReactTable({
    data: filtered,
    columns: columnDefs,
    state: { sorting, columnSizing: sizing, columnVisibility: effectiveVisibility },
    onSortingChange: setSorting,
    onColumnSizingChange: setSizing,
    onColumnVisibilityChange: setVisibility,
    enableSortingRemoval: false,
    enableMultiSort: false,
    getRowId: (row) => rowKey(row),
    getCoreRowModel: getCoreRowModel(),
    getSortedRowModel: getSortedRowModel(),
  })

  const setWidth = useCallback((columnId: string, width: number) => {
    setSizing((s) => ({ ...s, [columnId]: Math.round(width) }))
  }, [])

  /** Widest natural content of the column (header and cells) plus the cell padding. */
  const autofit = useCallback(
    (columnId: string) => {
      const root = tableRef.current
      if (!root) return
      let widest = 0
      root.querySelectorAll<HTMLElement>(`[data-col="${columnId}"]`).forEach((cell) => {
        const inner = cell.querySelector<HTMLElement>('.rtable__cell')
        if (!inner) return
        widest = Math.max(widest, inner.scrollWidth + (cell.offsetWidth - inner.clientWidth))
      })
      if (widest > 0) setWidth(columnId, widest)
    },
    [setWidth],
  )

  const hideableColumns = table.getAllLeafColumns().filter((c) => c.getCanHide())
  const visibleColumns = table.getVisibleLeafColumns()
  const hasActions = !!rowActions
  // On phones the pinned icon buttons would cover the data columns: they move into the overflow menu.
  const narrow = useMediaQuery(PHONE_QUERY)
  const actionsOf = (row: T): RowActions => {
    const a = rowActions!(row)
    if (!narrow || !a.primary?.length) return a
    const moved: MenuEntry[] = a.primary.map((p) => {
      const Icon = p.icon
      return { label: p.label, icon: <Icon aria-hidden />, onSelect: p.onSelect, danger: p.danger, disabled: p.disabled }
    })
    return { menu: a.menu?.length ? [...moved, { type: 'separator' }, ...a.menu] : moved }
  }
  const primaryId = narrow ? visibleColumns[0]?.id : undefined
  const flexIds = new Set(columns.filter((c) => c.flex && sizing[c.id] === undefined && c.id !== primaryId).map((c) => c.id))
  const hasFlex = visibleColumns.some((c) => flexIds.has(c.id))
  const widthOf = (c: { id: string; getSize: () => number }) => (c.id === primaryId ? Math.min(c.getSize(), NARROW_PRIMARY_WIDTH) : c.getSize())
  // the actions column is sized to its widest row so content never spills into the neighbouring column
  const actionSlots = hasActions
    ? filtered.reduce((max, r) => {
        const a = actionsOf(r)
        return Math.max(max, (a.primary?.length ?? 0) + (a.menu && a.menu.length > 0 ? 1 : 0))
      }, 1)
    : 0
  const sizeSum = visibleColumns.reduce((sum, c) => sum + widthOf(c), 0)
  // Runtime column sizes (data-driven, see tokenUsage.test.ts); the actions column adds its token-based width.
  const tableMinWidth = hasActions ? `calc(${sizeSum}px + var(--rt-actions-width))` : sizeSum

  let body: ReactNode
  if (error) {
    body = (
      <div className="rtable__error">
        <div role="alert">
          <EmptyState title={error} />
        </div>
        {onRetry && (
          <Button variant="secondary" onClick={onRetry}>
            {t('ResourceTable.retry')}
          </Button>
        )}
      </div>
    )
  } else if (isPending) {
    body = (
      <div className="rtable__skeleton" role="status" aria-label={t('ResourceTable.loadingLabel', { label })} aria-busy="true">
        {[0, 1, 2, 3].map((i) => (
          <Skeleton key={i} height="2.5rem" />
        ))}
      </div>
    )
  } else if ((rows ?? []).length === 0) {
    body = <EmptyState title={emptyMessage} />
  } else if (filtered.length === 0) {
    body = <EmptyState title={t('ResourceTable.noMatchesForTrim', { query: query.trim() })} />
  } else {
    body = (
      <div className="rtable__scroll">
        <table ref={tableRef} className="rtable__table" aria-label={label} style={{ ['--rt-action-slots' as string]: actionSlots, minWidth: tableMinWidth }}>
          <colgroup>
            {visibleColumns.map((c) => (
              <col key={c.id} style={flexIds.has(c.id) ? undefined : { width: widthOf(c) }} />
            ))}
            <col style={hasFlex ? { width: 0 } : undefined} />
            {hasActions && <col className="rtable__col-actions" />}
          </colgroup>
          <thead>
            {table.getHeaderGroups().map((group) => (
              <tr key={group.id}>
                {group.headers.map((header) => {
                  const column = header.column
                  const sorted = column.getIsSorted()
                  const text = String(column.columnDef.header)
                  return (
                    <th
                      key={header.id}
                      scope="col"
                      data-col={column.id}
                      aria-sort={column.getCanSort() ? (sorted === 'asc' ? 'ascending' : sorted === 'desc' ? 'descending' : 'none') : undefined}
                    >
                      <div className="rtable__cell">
                        {column.getCanSort() ? (
                          <button type="button" className="rtable__sort" onClick={column.getToggleSortingHandler()}>
                            {text}
                            <span aria-hidden="true" className="rtable__arrow">
                              {sorted === 'asc' ? <ArrowUp size={14} /> : sorted === 'desc' ? <ArrowDown size={14} /> : null}
                            </span>
                          </button>
                        ) : (
                          text
                        )}
                      </div>
                      <ResizeHandle
                        name={text}
                        width={column.getSize()}
                        min={column.columnDef.minSize ?? MIN_WIDTH}
                        max={column.columnDef.maxSize ?? MAX_WIDTH}
                        onResize={(w) => setWidth(column.id, w)}
                        onAutofit={() => autofit(column.id)}
                      />
                    </th>
                  )
                })}
                <th className="rtable__filler" aria-hidden="true" />
                {hasActions && (
                  <th scope="col" className="rtable__actions-cell">
                    <span className="visually-hidden">{t('ResourceTable.actions')}</span>
                  </th>
                )}
              </tr>
            ))}
          </thead>
          <tbody>
            {table.getRowModel().rows.map((row) => {
              const actions = hasActions ? actionsOf(row.original) : undefined
              const name = rowLabel?.(row.original)
              return (
                <tr key={row.id}>
                  {row.getVisibleCells().map((cell) => (
                    <td key={cell.id} data-col={cell.column.id}>
                      <div className="rtable__cell">{flexRender(cell.column.columnDef.cell, cell.getContext())}</div>
                    </td>
                  ))}
                  <td className="rtable__filler" aria-hidden="true" />
                  {hasActions && (
                    <td className="rtable__actions-cell">
                      <div className="rtable__actions">
                        {actions?.primary?.map((a) => (
                          <IconButton
                            key={a.label}
                            icon={a.icon}
                            variant={a.danger ? 'danger' : 'ghost'}
                            aria-label={name ? `${a.label} ${name}` : a.label}
                            disabled={a.disabled}
                            onClick={a.onSelect}
                          />
                        ))}
                        {actions?.menu && actions.menu.length > 0 && (
                          <Menu
                            trigger={<IconButton icon={Ellipsis} aria-label={name ? t('ResourceTable.moreActionsForName', { name }) : t('ResourceTable.moreActions')} />}
                            items={actions.menu}
                          />
                        )}
                      </div>
                    </td>
                  )}
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>
    )
  }

  return (
    <div className="rtable" data-density={density}>
      <div className="rtable__toolbar">
        <SearchField
          aria-label={t('ResourceTable.searchLabel', { label })}
          placeholder={t('ResourceTable.searchLabel', { label })}
          value={query}
          onChange={(e) => setQuery(e.target.value)}
        />
        {toolbar}
        <span className="rtable__spacer" />
        {hideableColumns.length > 0 && (
          <Menu
            trigger={<IconButton icon={Columns3} aria-label={t('ResourceTable.columns')} />}
            items={hideableColumns.map((c) => ({
              label: String(c.columnDef.header),
              icon: c.getIsVisible() ? <Check size={16} strokeWidth={2} /> : <span className="rtable__nocheck" />,
              onSelect: () => c.toggleVisibility(!c.getIsVisible()),
              // the last visible column stays
              disabled: c.getIsVisible() && visibleColumns.length === 1,
            }))}
          />
        )}
        <IconButton
          icon={density === 'compact' ? Rows4 : Rows3}
          aria-label={density === 'compact' ? t('ResourceTable.useComfortableDensity') : t('ResourceTable.useCompactDensity')}
          onClick={() => setDensity((d) => (d === 'compact' ? 'comfortable' : 'compact'))}
        />
      </div>
      {body}
    </div>
  )
}

interface HandleProps {
  name: string
  width: number
  min: number
  max: number
  onResize: (width: number) => void
  onAutofit: () => void
}

/** Drag/keyboard handle on a column's right edge. Pointer capture keeps the edge under the cursor 1:1. */
function ResizeHandle({ name, width, min, max, onResize, onAutofit }: HandleProps) {
  const { t } = useTranslation('common')
  const drag = useRef<{ x: number; width: number } | null>(null)

  function down(e: PointerEvent<HTMLDivElement>) {
    if (e.button !== 0) return
    e.currentTarget.setPointerCapture?.(e.pointerId)
    drag.current = { x: e.clientX, width }
  }
  function move(e: PointerEvent<HTMLDivElement>) {
    if (!drag.current) return
    onResize(drag.current.width + (e.clientX - drag.current.x))
  }
  function up(e: PointerEvent<HTMLDivElement>) {
    drag.current = null
    if (e.currentTarget.hasPointerCapture?.(e.pointerId)) e.currentTarget.releasePointerCapture(e.pointerId)
  }
  function key(e: KeyboardEvent<HTMLDivElement>) {
    const step = e.shiftKey ? KEY_STEP_LARGE : KEY_STEP
    if (e.key === 'ArrowRight') onResize(width + step)
    else if (e.key === 'ArrowLeft') onResize(width - step)
    else if (e.key === 'Enter') onAutofit()
    else return
    e.preventDefault()
  }

  return (
    <div
      role="separator"
      aria-orientation="vertical"
      aria-label={t('ResourceTable.resizeNameColumn', { name })}
      aria-valuenow={width}
      aria-valuemin={min}
      aria-valuemax={max}
      tabIndex={0}
      className="rtable__resize"
      onPointerDown={down}
      onPointerMove={move}
      onPointerUp={up}
      onPointerCancel={up}
      onLostPointerCapture={() => (drag.current = null)}
      onDoubleClick={onAutofit}
      onKeyDown={key}
    />
  )
}
