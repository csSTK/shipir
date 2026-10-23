import { useEffect, useRef, type ReactNode } from 'react'
import './DeploymentUi.css'

/** Modal dialog shell: focus moves in and is restored, Escape and scrim click close it. */
export function DeploymentDialog({ title, titleId, onClose, children }: { title: string; titleId: string; onClose: () => void; children: ReactNode }) {
  const ref = useRef<HTMLDivElement>(null)
  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null
    ref.current?.focus()
    return () => previous?.focus()
  }, [])
  return (
    <div className="sk-scrim" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div
        ref={ref}
        className="sk-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        tabIndex={-1}
        onKeyDown={(e) => e.key === 'Escape' && onClose()}
      >
        <h2 id={titleId} className="sk-dialog__title">
          {title}
        </h2>
        {children}
      </div>
    </div>
  )
}
