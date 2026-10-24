import type { ReactNode } from 'react'
import { Dialog } from '../../shared/ui/index.ts'
import './DeploymentUi.css'

/**
 * Modal shell for deployment-style forms (also used by the create dialogs of other resources): the shared
 * `Dialog` (focus trap and restore, Esc/scrim close, spring). `titleId` is kept for existing callers; Radix names the dialog itself.
 */
export function DeploymentDialog({ title, onClose, children }: { title: string; titleId?: string; onClose: () => void; children: ReactNode }) {
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()} title={title}>
      {children}
    </Dialog>
  )
}
