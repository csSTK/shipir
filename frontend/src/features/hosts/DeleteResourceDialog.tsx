import { useState } from 'react'
import { ConfirmDialog } from '../deployments/ConfirmDialog.tsx'
import { describeDeleteError } from './deleteResource.ts'
import './DeleteResourceDialog.css'

interface Props {
  /** Singular noun: "container". */
  kind: string
  name: string
  /** Offers the "Force (stop and remove)" checkbox (running containers). */
  forceOption?: boolean
  /** Shown when the resource belongs to a managed deployment. */
  warning?: string | null
  onDelete: (force: boolean) => Promise<unknown>
  onClose: () => void
}

/** Confirmation for deleting a container, image, volume or network. Errors stay inline. */
export function DeleteResourceDialog({ kind, name, forceOption = false, warning = null, onDelete, onClose }: Props) {
  const [force, setForce] = useState(false)
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function confirm() {
    setPending(true)
    setError(null)
    try {
      await onDelete(forceOption && force)
      onClose()
    } catch (e) {
      setError(describeDeleteError(e))
      setPending(false)
    }
  }

  return (
    <ConfirmDialog title={`Delete ${kind}`} confirmLabel="Delete" danger pending={pending} error={error} onConfirm={() => void confirm()} onClose={onClose}>
      Delete {kind} <strong>{name}</strong>? This cannot be undone.
      {warning && (
        <span className="delres__warning" role="note">
          {warning}
        </span>
      )}
      {forceOption && (
        <label className="delres__check">
          <input type="checkbox" checked={force} onChange={(e) => setForce(e.target.checked)} /> Force (stop and remove)
        </label>
      )}
    </ConfirmDialog>
  )
}
