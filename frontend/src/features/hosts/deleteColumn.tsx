import type { ReactNode } from 'react'
import type { ResourceColumn } from '../../shared/ui/index.ts'

/** Trailing "Delete" button column for `ResourceTable` (ADMIN/OPERATOR pages only). */
export function deleteColumn<T>(kind: string, nameOf: (row: T) => string, onRequest: (row: T) => void): ResourceColumn<T> {
  return {
    id: 'actions',
    header: 'Actions',
    render: (row): ReactNode => (
      <button type="button" className="sk-btn sk-btn--danger-text delres__btn" aria-label={`Delete ${kind} ${nameOf(row)}`} onClick={() => onRequest(row)}>
        Delete
      </button>
    ),
  }
}
