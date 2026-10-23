import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { ResourceTable, type ResourceColumn } from './ResourceTable.tsx'

afterEach(cleanup)

interface Row {
  id: string
  name: string
  size: number
}
const columns: ResourceColumn<Row>[] = [
  { id: 'name', header: 'Name', render: (r) => r.name, sortValue: (r) => r.name },
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
})
