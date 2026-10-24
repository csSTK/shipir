import type { ReactNode } from 'react'
import type { ResourceColumn } from '../../shared/ui/index.ts'
import { Button } from '../../shared/ui/index.ts'

/** Trailing "Delete" button column for `ResourceTable` (ADMIN/OPERATOR pages only). */
export function deleteColumn<T>(kind: string, nameOf: (row: T) => string, onRequest: (row: T) => void): ResourceColumn<T> {
  return {
    id: 'actions',
    header: 'Actions',
    render: (row): ReactNode => (
      <Button variant="danger" size="sm" aria-label={`Delete ${kind} ${nameOf(row)}`} onClick={() => onRequest(row)}>
        Delete
      </Button>
    ),
  }
}
