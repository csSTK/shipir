import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { Play, Trash2 } from 'lucide-react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ResourceTable, type ResourceColumn } from './ResourceTable.tsx'

beforeEach(() => window.localStorage.clear())
afterEach(cleanup)

interface Row {
  id: string
  name: string
  size: number
}
const columns: ResourceColumn<Row>[] = [
  { id: 'name', header: 'Name', render: (r) => r.name, sortValue: (r) => r.name, hideable: false },
  { id: 'size', header: 'Size', render: (r) => String(r.size), sortValue: (r) => r.size },
  { id: 'plain', header: 'Plain', render: () => 'x' },
]
const rows: Row[] = [
  { id: '1', name: 'beta', size: 5 },
  { id: '2', name: 'Alpha', size: 20 },
  { id: '3', name: 'gamma', size: 1 },
]

function setup(over: Partial<Parameters<typeof ResourceTable<Row>>[0]> = {}) {
  return render(
    <ResourceTable<Row>
      label="things"
      columns={columns}
      rows={rows}
      rowKey={(r) => r.id}
      searchText={(r) => r.name}
      defaultSort="name"
      emptyMessage="Nothing here."
      {...over}
    />,
  )
}

function names() {
  return screen
    .getAllByRole('row')
    .slice(1)
    .map((r) => within(r).getAllByRole('cell')[0].textContent)
}

function sizeHandle() {
  return screen.getByRole('separator', { name: 'Resize Size column' })
}

describe('ResourceTable', () => {
  it('sorts by the default column case-insensitively', () => {
    setup()
    expect(names()).toEqual(['Alpha', 'beta', 'gamma'])
    expect(screen.getByRole('columnheader', { name: /Name/ })).toHaveAttribute('aria-sort', 'ascending')
  })

  it('sorts by another column and toggles direction', () => {
    setup()
    fireEvent.click(screen.getByRole('button', { name: 'Size' }))
    expect(names()).toEqual(['gamma', 'beta', 'Alpha'])
    fireEvent.click(screen.getByRole('button', { name: 'Size' }))
    expect(names()).toEqual(['Alpha', 'beta', 'gamma'])
    expect(screen.getByRole('columnheader', { name: /Size/ })).toHaveAttribute('aria-sort', 'descending')
  })

  it('does not offer sorting for columns without a sort value', () => {
    setup()
    expect(screen.queryByRole('button', { name: 'Plain' })).not.toBeInTheDocument()
  })

  it('filters by the search text', () => {
    setup()
    fireEvent.change(screen.getByRole('searchbox', { name: 'Search things' }), { target: { value: 'ET' } })
    expect(names()).toEqual(['beta'])
  })

  it('shows a no-match message when the search finds nothing', () => {
    setup()
    fireEvent.change(screen.getByRole('searchbox', { name: 'Search things' }), { target: { value: 'zzz' } })
    expect(screen.getByText('No matches for "zzz".')).toBeInTheDocument()
  })

  it('shows a skeleton while loading', () => {
    setup({ rows: undefined, isPending: true })
    expect(screen.getByRole('status', { name: 'Loading things' })).toHaveAttribute('aria-busy', 'true')
  })

  it('shows the error with a retry action', () => {
    let retried = 0
    setup({ rows: undefined, error: 'The connection timed out.', onRetry: () => retried++ })
    expect(screen.getByRole('alert')).toHaveTextContent('The connection timed out.')
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }))
    expect(retried).toBe(1)
  })

  it('shows the empty message', () => {
    setup({ rows: [] })
    expect(screen.getByText('Nothing here.')).toBeInTheDocument()
  })

  describe('resizing', () => {
    it('follows the pointer 1:1 and keeps going while captured', () => {
      setup()
      const handle = sizeHandle()
      expect(handle).toHaveAttribute('aria-valuenow', '160')
      fireEvent.pointerDown(handle, { button: 0, clientX: 100, pointerId: 1 })
      fireEvent.pointerMove(handle, { clientX: 140, pointerId: 1 })
      expect(sizeHandle()).toHaveAttribute('aria-valuenow', '200')
      fireEvent.pointerMove(handle, { clientX: 110, pointerId: 1 })
      expect(sizeHandle()).toHaveAttribute('aria-valuenow', '170')
      fireEvent.pointerUp(handle, { pointerId: 1 })
      fireEvent.pointerMove(handle, { clientX: 400, pointerId: 1 })
      expect(sizeHandle()).toHaveAttribute('aria-valuenow', '170')
    })

    it('never goes below the minimum width', () => {
      setup()
      fireEvent.pointerDown(sizeHandle(), { button: 0, clientX: 500, pointerId: 1 })
      fireEvent.pointerMove(sizeHandle(), { clientX: 0, pointerId: 1 })
      expect(sizeHandle()).toHaveAttribute('aria-valuenow', '64')
    })

    it('resizes with the arrow keys (shift = larger steps)', () => {
      setup()
      fireEvent.keyDown(sizeHandle(), { key: 'ArrowRight' })
      expect(sizeHandle()).toHaveAttribute('aria-valuenow', '176')
      fireEvent.keyDown(sizeHandle(), { key: 'ArrowLeft', shiftKey: true })
      expect(sizeHandle()).toHaveAttribute('aria-valuenow', '112')
    })

    it('fits the content on double-click', () => {
      setup()
      const spy = vi.spyOn(HTMLElement.prototype, 'scrollWidth', 'get').mockReturnValue(230)
      vi.spyOn(HTMLElement.prototype, 'offsetWidth', 'get').mockReturnValue(260)
      vi.spyOn(HTMLElement.prototype, 'clientWidth', 'get').mockReturnValue(228)
      fireEvent.doubleClick(sizeHandle())
      expect(sizeHandle()).toHaveAttribute('aria-valuenow', '262')
      spy.mockRestore()
      vi.restoreAllMocks()
    })

    it('restores saved widths after a reload', () => {
      const first = setup()
      fireEvent.keyDown(sizeHandle(), { key: 'ArrowRight' })
      first.unmount()
      setup()
      expect(sizeHandle()).toHaveAttribute('aria-valuenow', '176')
    })

    it('keeps preferences separate per table id', () => {
      const first = setup({ id: 'a' })
      fireEvent.keyDown(sizeHandle(), { key: 'ArrowRight' })
      first.unmount()
      setup({ id: 'b' })
      expect(sizeHandle()).toHaveAttribute('aria-valuenow', '160')
    })
  })

  describe('column visibility', () => {
    it('hides a column through the menu and remembers it', async () => {
      const first = setup()
      fireEvent.pointerDown(screen.getByRole('button', { name: 'Columns' }), { button: 0, ctrlKey: false })
      fireEvent.click(await screen.findByRole('menuitem', { name: 'Plain' }))
      expect(screen.queryByRole('columnheader', { name: /Plain/ })).not.toBeInTheDocument()
      first.unmount()
      setup()
      expect(screen.queryByRole('columnheader', { name: /Plain/ })).not.toBeInTheDocument()
      expect(screen.getByRole('columnheader', { name: /Size/ })).toBeInTheDocument()
    })

    it('does not list columns marked as not hideable', async () => {
      setup()
      fireEvent.pointerDown(screen.getByRole('button', { name: 'Columns' }), { button: 0, ctrlKey: false })
      await screen.findByRole('menuitem', { name: 'Plain' })
      expect(screen.queryByRole('menuitem', { name: 'Name' })).not.toBeInTheDocument()
    })

    it('starts without defaultHidden columns', () => {
      setup({ columns: columns.map((c) => (c.id === 'plain' ? { ...c, defaultHidden: true } : c)) })
      expect(screen.queryByRole('columnheader', { name: /Plain/ })).not.toBeInTheDocument()
    })

    it('ignores corrupt saved preferences', () => {
      window.localStorage.setItem('shipir.table.things', '{nope')
      setup()
      expect(names()).toEqual(['Alpha', 'beta', 'gamma'])
    })
  })

  describe('density', () => {
    it('toggles and remembers compact density', () => {
      const first = setup()
      fireEvent.click(screen.getByRole('button', { name: 'Use compact density' }))
      expect(document.querySelector('.rtable')).toHaveAttribute('data-density', 'compact')
      first.unmount()
      setup()
      expect(document.querySelector('.rtable')).toHaveAttribute('data-density', 'compact')
    })
  })

  describe('row actions', () => {
    it('renders pinned icon actions and an overflow menu per row', async () => {
      const start = vi.fn()
      const remove = vi.fn()
      setup({
        rowLabel: (r) => r.name,
        rowActions: (r) => ({
          primary: [{ label: 'Start', icon: Play, onSelect: () => start(r.id) }],
          menu: [{ label: 'Delete', onSelect: () => remove(r.id), danger: true }],
        }),
      })
      fireEvent.click(screen.getByRole('button', { name: 'Start beta' }))
      expect(start).toHaveBeenCalledWith('1')

      fireEvent.pointerDown(screen.getByRole('button', { name: 'More actions for beta' }), { button: 0, ctrlKey: false })
      fireEvent.click(await screen.findByRole('menuitem', { name: 'Delete' }))
      expect(remove).toHaveBeenCalledWith('1')
    })

    it('marks the actions column as pinned and disables disabled actions', () => {
      setup({
        rowLabel: (r) => r.name,
        rowActions: () => ({ primary: [{ label: 'Remove', icon: Trash2, onSelect: () => {}, disabled: true, danger: true }] }),
      })
      expect(screen.getAllByRole('button', { name: /^Remove / })[0]).toHaveAttribute('aria-disabled', 'true')
      expect(document.querySelector('th.rtable__actions-cell')).toBeInTheDocument()
    })
  })

  describe('row actions on narrow screens', () => {
    afterEach(() => vi.unstubAllGlobals())

    it('collapses the icon actions into the overflow menu, one slot wide', async () => {
      vi.stubGlobal('matchMedia', (query: string) => ({
        matches: true,
        media: query,
        addEventListener: () => {},
        removeEventListener: () => {},
      }))
      const start = vi.fn()
      setup({
        rowLabel: (r) => r.name,
        rowActions: (r) => ({
          primary: [{ label: 'Start', icon: Play, onSelect: () => start(r.id) }],
          menu: [{ label: 'Delete', onSelect: () => {}, danger: true }],
        }),
      })
      expect(screen.queryByRole('button', { name: 'Start beta' })).not.toBeInTheDocument()
      expect(document.querySelector('table')?.style.getPropertyValue('--rt-action-slots')).toBe('1')
      fireEvent.pointerDown(screen.getByRole('button', { name: 'More actions for beta' }), { button: 0, ctrlKey: false })
      fireEvent.click(await screen.findByRole('menuitem', { name: 'Start' }))
      expect(start).toHaveBeenCalledWith('1')
      expect(await screen.findByRole('menuitem', { name: 'Delete' })).toBeInTheDocument()
    })
  })

  describe('column sizing', () => {
    const sized: ResourceColumn<Row>[] = [
      { id: 'name', header: 'Name', render: (r) => r.name, sortValue: (r) => r.name, hideable: false, flex: true },
      { id: 'size', header: 'Size', render: (r) => String(r.size), defaultWidth: 90, isEmpty: (r) => r.size === 0 },
    ]

    function colWidths() {
      return Array.from(document.querySelectorAll<HTMLElement>('col')).map((c) => c.style.width)
    }

    it('leaves flexible columns without a fixed width so they take the free space', () => {
      setup({ columns: sized })
      const [name, size] = colWidths()
      expect(name).toBe('')
      expect(size).toBe('90px')
    })

    it('turns a flexible column fixed once the user resizes it', () => {
      setup({ columns: sized })
      fireEvent.keyDown(screen.getByRole('separator', { name: 'Resize Name column' }), { key: 'ArrowRight' })
      expect(colWidths()[0]).not.toBe('')
    })

    it('clamps saved widths below the column minimum on load', () => {
      window.localStorage.setItem('shipir.table.things', JSON.stringify({ widths: { size: 50 }, hidden: [], density: 'comfortable' }))
      setup({ columns: [sized[0], { ...sized[1], minWidth: 140 }] })
      expect(colWidths()[1]).toBe('140px')
    })

    it('hides a column that is empty for every row', () => {
      setup({ columns: sized, rows: rows.map((r) => ({ ...r, size: 0 })) })
      expect(screen.queryByRole('columnheader', { name: /Size/ })).not.toBeInTheDocument()
    })

    it('keeps a column that has a value in at least one row', () => {
      setup({ columns: sized, rows: [{ id: '1', name: 'a', size: 0 }, { id: '2', name: 'b', size: 3 }] })
      expect(screen.getByRole('columnheader', { name: /Size/ })).toBeInTheDocument()
    })

    it('lets the user turn an auto-hidden column on again', async () => {
      setup({ columns: sized, rows: rows.map((r) => ({ ...r, size: 0 })) })
      fireEvent.pointerDown(screen.getByRole('button', { name: 'Columns' }), { button: 0, ctrlKey: false })
      fireEvent.click(await screen.findByRole('menuitem', { name: /Size/ }))
      expect(screen.getByRole('columnheader', { name: /Size/, hidden: true })).toBeInTheDocument()
    })
  })
})
