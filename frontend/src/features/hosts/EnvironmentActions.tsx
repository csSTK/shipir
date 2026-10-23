import { useEffect, useId, useRef, useState, type FormEvent, type ReactNode } from 'react'
import { ApiError } from '../../shared/api/client.ts'
import { useDeleteHost, useRenameHost, type HostOverview } from './api.ts'
import './EnvironmentActions.css'

const NAME_MAX = 100

function Modal({ title, titleId, onClose, children }: { title: string; titleId: string; onClose: () => void; children: ReactNode }) {
  const ref = useRef<HTMLDivElement>(null)
  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null
    ref.current?.focus()
    return () => previous?.focus()
  }, [])
  return (
    <div className="env-scrim" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div
        ref={ref}
        className="env-dialog env-dialog--narrow"
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        tabIndex={-1}
        onKeyDown={(e) => e.key === 'Escape' && onClose()}
      >
        <h2 id={titleId} className="env-dialog__title">
          {title}
        </h2>
        {children}
      </div>
    </div>
  )
}

function renameMessage(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.code === 'HOST_NAME_TAKEN') return 'This name is already in use.'
    if (error.code === 'HOST_NOT_FOUND') return 'This environment no longer exists.'
    if (error.code === 'VALIDATION_ERROR' && error.details?.length) return error.details.join(' ')
  }
  return 'Could not rename the environment. Try again.'
}

function RenameDialog({ env, onClose }: { env: HostOverview; onClose: () => void }) {
  const titleId = useId()
  const [name, setName] = useState(env.name)
  const [problem, setProblem] = useState<string | null>(null)
  const rename = useRenameHost()

  function submit(e: FormEvent) {
    e.preventDefault()
    const trimmed = name.trim()
    if (trimmed.length < 1 || trimmed.length > NAME_MAX) {
      setProblem(`The name must be 1 to ${NAME_MAX} characters.`)
      return
    }
    setProblem(null)
    rename.mutate({ id: env.id, name: trimmed }, { onSuccess: onClose, onError: (err) => setProblem(renameMessage(err)) })
  }

  return (
    <Modal title="Rename environment" titleId={titleId} onClose={onClose}>
      <form onSubmit={submit} className="env-dialog__form">
        <label className="env-field">
          <span>Name</span>
          <input value={name} onChange={(e) => setName(e.target.value)} autoFocus autoComplete="off" />
        </label>
        {problem && (
          <p role="alert" className="env-dialog__error">
            {problem}
          </p>
        )}
        <div className="env-dialog__actions">
          <button type="button" className="env-btn env-btn--plain" onClick={onClose}>
            Cancel
          </button>
          <button type="submit" className="env-btn env-btn--primary" disabled={rename.isPending}>
            Save
          </button>
        </div>
      </form>
    </Modal>
  )
}

function DeleteDialog({ env, onClose }: { env: HostOverview; onClose: () => void }) {
  const titleId = useId()
  const [problem, setProblem] = useState<string | null>(null)
  const remove = useDeleteHost()

  function confirm() {
    setProblem(null)
    remove.mutate(env.id, {
      onSuccess: onClose,
      onError: (err) =>
        setProblem(
          err instanceof ApiError && err.code === 'HOST_NOT_FOUND'
            ? 'This environment no longer exists.'
            : 'Could not delete the environment. Try again.',
        ),
    })
  }

  return (
    <Modal title="Delete environment" titleId={titleId} onClose={onClose}>
      <p className="env-dialog__text">
        Delete <strong>{env.name}</strong>? Its stacks are removed from Shipir as well. Containers already running on the Docker host are not touched.
      </p>
      {problem && (
        <p role="alert" className="env-dialog__error">
          {problem}
        </p>
      )}
      <div className="env-dialog__actions">
        <button type="button" className="env-btn env-btn--plain" onClick={onClose}>
          Cancel
        </button>
        <button type="button" className="env-btn env-btn--danger" onClick={confirm} disabled={remove.isPending}>
          Delete
        </button>
      </div>
    </Modal>
  )
}

/** Rename and delete buttons for one overview row (ADMIN only; the caller decides). */
export function EnvironmentActions({ env }: { env: HostOverview }) {
  const [dialog, setDialog] = useState<'rename' | 'delete' | null>(null)
  const close = () => setDialog(null)
  return (
    <div className="env-row__actions">
      <button type="button" className="env-btn env-btn--plain" aria-label={`Rename ${env.name}`} onClick={() => setDialog('rename')}>
        Rename
      </button>
      <button type="button" className="env-btn env-btn--plain env-btn--danger-text" aria-label={`Delete ${env.name}`} onClick={() => setDialog('delete')}>
        Delete
      </button>
      {dialog === 'rename' && <RenameDialog env={env} onClose={close} />}
      {dialog === 'delete' && <DeleteDialog env={env} onClose={close} />}
    </div>
  )
}
