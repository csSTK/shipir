import { useId, useState, type FormEvent } from 'react'
import { MoreHorizontal, Pencil, Tag, Trash2 } from 'lucide-react'
import { ApiError } from '../../shared/api/client.ts'
import { Button, ConfirmDialog, Dialog, IconButton, Menu } from '../../shared/ui/index.ts'
import { useDeleteHost, useRenameHost, type HostOverview } from './api.ts'
import { EditTagsDialog } from './EditTagsDialog.tsx'
import './EnvironmentActions.css'

const NAME_MAX = 100

function renameMessage(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.code === 'HOST_NAME_TAKEN') return 'This name is already in use.'
    if (error.code === 'HOST_NOT_FOUND') return 'This environment no longer exists.'
    if (error.code === 'VALIDATION_ERROR' && error.details?.length) return error.details.join(' ')
  }
  return 'Could not rename the environment. Try again.'
}

function RenameDialog({ env, open, onClose }: { env: HostOverview; open: boolean; onClose: () => void }) {
  const formId = useId()
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
    <Dialog
      open={open}
      onOpenChange={(next) => !next && onClose()}
      title="Rename environment"
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button variant="primary" type="submit" form={formId} loading={rename.isPending}>
            Save
          </Button>
        </>
      }
    >
      <form id={formId} onSubmit={submit} className="env-form-field">
        <label className="env-form-field__label">
          <span>Name</span>
          <input className="env-form-field__input" value={name} onChange={(e) => setName(e.target.value)} autoFocus autoComplete="off" />
        </label>
        {problem && (
          <p role="alert" className="env-form-field__error">
            {problem}
          </p>
        )}
      </form>
    </Dialog>
  )
}

function DeleteDialog({ env, open, onClose, onDeleted }: { env: HostOverview; open: boolean; onClose: () => void; onDeleted?: () => void }) {
  const [problem, setProblem] = useState<string | null>(null)
  const remove = useDeleteHost()

  function confirm() {
    setProblem(null)
    remove.mutate(env.id, {
      onSuccess: () => {
        onClose()
        onDeleted?.()
      },
      onError: (err) =>
        setProblem(
          err instanceof ApiError && err.code === 'HOST_NOT_FOUND'
            ? 'This environment no longer exists.'
            : 'Could not delete the environment. Try again.',
        ),
    })
  }

  return (
    <ConfirmDialog
      open={open}
      onOpenChange={(next) => !next && onClose()}
      title="Delete environment"
      description={`Delete ${env.name}? Its deployments are removed from Shipir as well. Containers already running on the Docker host are not touched.`}
      confirmLabel="Delete"
      danger
      loading={remove.isPending}
      onConfirm={confirm}
    >
      {problem && (
        <p role="alert" className="env-form-field__error">
          {problem}
        </p>
      )}
    </ConfirmDialog>
  )
}

/**
 * Overflow menu with Rename, Edit tags and Delete (ADMIN only; the caller decides). Dialogs stay mounted so
 * they animate out; `session` gives every opening fresh form state.
 */
export function EnvironmentActions({ env, onDeleted }: { env: HostOverview; onDeleted?: () => void }) {
  const [dialog, setDialog] = useState<'rename' | 'tags' | 'delete' | null>(null)
  const [session, setSession] = useState(0)
  const show = (which: 'rename' | 'tags' | 'delete') => {
    setSession((s) => s + 1)
    setDialog(which)
  }
  const close = () => setDialog(null)
  return (
    <>
      <Menu
        trigger={<IconButton icon={MoreHorizontal} aria-label={`Actions for ${env.name}`} />}
        items={[
          { label: 'Rename', icon: <Pencil size={16} strokeWidth={1.75} />, onSelect: () => show('rename') },
          { label: 'Edit tags', icon: <Tag size={16} strokeWidth={1.75} />, onSelect: () => show('tags') },
          { type: 'separator' },
          { label: 'Delete', icon: <Trash2 size={16} strokeWidth={1.75} />, danger: true, onSelect: () => show('delete') },
        ]}
      />
      <RenameDialog key={`rename-${session}`} env={env} open={dialog === 'rename'} onClose={close} />
      <EditTagsDialog key={`tags-${session}`} env={env} open={dialog === 'tags'} onClose={close} />
      <DeleteDialog key={`delete-${session}`} env={env} open={dialog === 'delete'} onClose={close} onDeleted={onDeleted} />
    </>
  )
}
