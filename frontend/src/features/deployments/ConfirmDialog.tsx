import type { ReactNode } from 'react'
import { Button, Dialog } from '../../shared/ui/index.ts'
import './DeploymentUi.css'

interface Props {
  title: string
  confirmLabel: string
  /** Destructive confirms get the red button. */
  danger?: boolean
  pending?: boolean
  error?: string | null
  onConfirm: () => void
  onClose: () => void
  children: ReactNode
  /** Rendered below the text (e.g. a diff); kept out of the paragraph. */
  details?: ReactNode
}

/** Confirmation for rollback, delete and stop; content-rich variant of the shared `ConfirmDialog` (details, inline error). */
export function ConfirmDialog({ title, confirmLabel, danger = false, pending = false, error = null, onConfirm, onClose, children, details }: Props) {
  return (
    <Dialog
      open
      onOpenChange={(open) => !open && onClose()}
      title={title}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button variant={danger ? 'danger' : 'primary'} onClick={onConfirm} disabled={pending} loading={pending}>
            {confirmLabel}
          </Button>
        </>
      }
    >
      <p className="sk-dialog__text">{children}</p>
      {details}
      {error && (
        <p role="alert" className="sk-error">
          {error}
        </p>
      )}
    </Dialog>
  )
}
