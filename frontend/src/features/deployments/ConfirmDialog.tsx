import { useId, type ReactNode } from 'react'
import { DeploymentDialog } from './DeploymentDialog.tsx'

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

/** Confirmation for rollback, delete and stop. */
export function ConfirmDialog({ title, confirmLabel, danger = false, pending = false, error = null, onConfirm, onClose, children, details }: Props) {
  const titleId = useId()
  return (
    <DeploymentDialog title={title} titleId={titleId} onClose={onClose}>
      <p className="sk-dialog__text">{children}</p>
      {details}
      {error && (
        <p role="alert" className="sk-error">
          {error}
        </p>
      )}
      <div className="sk-dialog__actions">
        <button type="button" className="sk-btn sk-btn--plain" onClick={onClose}>
          Cancel
        </button>
        <button type="button" className={`sk-btn ${danger ? 'sk-btn--danger' : 'sk-btn--primary'}`} onClick={onConfirm} disabled={pending}>
          {confirmLabel}
        </button>
      </div>
    </DeploymentDialog>
  )
}
