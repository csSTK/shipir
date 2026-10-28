import { useId, useState, type FormEvent } from 'react'
import { MoreHorizontal, Pencil, Tag, Trash2 } from 'lucide-react'
import { ApiError } from '../../shared/api/client.ts'
import { Button, ConfirmDialog, Dialog, IconButton, Menu, TextField } from '../../shared/ui/index.ts'
import { useDeleteHost, useRenameHost, type HostOverview } from './api.ts'
import { EditTagsDialog } from './EditTagsDialog.tsx'
import { useTranslation } from 'react-i18next'
import i18n from 'i18next'

const NAME_MAX = 100

function renameMessage(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.code === 'HOST_NAME_TAKEN') return i18n.t('hosts:EnvironmentActions.thisNameIsAlreadyIn')
    if (error.code === 'HOST_NOT_FOUND') return i18n.t('hosts:EnvironmentActions.thisEnvironmentNoLongerExists')
    if (error.code === 'VALIDATION_ERROR' && error.details?.length) return error.details.join(' ')
  }
  return i18n.t('hosts:EnvironmentActions.couldNotRenameTheEnvironment')
}

function RenameDialog({ env, open, onClose }: { env: HostOverview; open: boolean; onClose: () => void }) {
  const { t } = useTranslation('hosts')
  const formId = useId()
  const [name, setName] = useState(env.name)
  const [problem, setProblem] = useState<string | null>(null)
  const rename = useRenameHost()

  function submit(e: FormEvent) {
    e.preventDefault()
    const trimmed = name.trim()
    if (trimmed.length < 1 || trimmed.length > NAME_MAX) {
      setProblem(t('EnvironmentActions.theNameMustBe1', { NAME_MAX }))
      return
    }
    setProblem(null)
    rename.mutate({ id: env.id, name: trimmed }, { onSuccess: onClose, onError: (err) => setProblem(renameMessage(err)) })
  }

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => !next && onClose()}
      title={t('EnvironmentActions.renameEnvironment')}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            {t('EnvironmentActions.cancel')}
          </Button>
          <Button variant="primary" type="submit" form={formId} loading={rename.isPending}>
            {t('EnvironmentActions.save')}
          </Button>
        </>
      }
    >
      <form id={formId} onSubmit={submit} className="ui-form">
        <TextField label={t('EnvironmentActions.name')} value={name} onChange={(e) => setName(e.target.value)} error={problem} autoFocus autoComplete="off" />
      </form>
    </Dialog>
  )
}

function DeleteDialog({ env, open, onClose, onDeleted }: { env: HostOverview; open: boolean; onClose: () => void; onDeleted?: () => void }) {
  const { t } = useTranslation('hosts')
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
            ? t('EnvironmentActions.thisEnvironmentNoLongerExists')
            : t('EnvironmentActions.couldNotDeleteTheEnvironment'),
        ),
    })
  }

  return (
    <ConfirmDialog
      open={open}
      onOpenChange={(next) => !next && onClose()}
      title={t('EnvironmentActions.deleteEnvironment')}
      description={t('EnvironmentActions.deleteNameItsDeploymentsAre', { name: env.name })}
      confirmLabel={t('EnvironmentActions.delete')}
      danger
      loading={remove.isPending}
      onConfirm={confirm}
    >
      {problem && (
        <p role="alert" className="ui-error">
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
  const { t } = useTranslation('hosts')
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
        trigger={<IconButton icon={MoreHorizontal} aria-label={t('EnvironmentActions.actionsForName', { name: env.name })} />}
        items={[
          { label: t('EnvironmentActions.rename'), icon: <Pencil size={16} strokeWidth={1.75} />, onSelect: () => show('rename') },
          { label: t('EnvironmentActions.editTags'), icon: <Tag size={16} strokeWidth={1.75} />, onSelect: () => show('tags') },
          { type: 'separator' },
          { label: t('EnvironmentActions.delete'), icon: <Trash2 size={16} strokeWidth={1.75} />, danger: true, onSelect: () => show('delete') },
        ]}
      />
      <RenameDialog key={`rename-${session}`} env={env} open={dialog === 'rename'} onClose={close} />
      <EditTagsDialog key={`tags-${session}`} env={env} open={dialog === 'tags'} onClose={close} />
      <DeleteDialog key={`delete-${session}`} env={env} open={dialog === 'delete'} onClose={close} onDeleted={onDeleted} />
    </>
  )
}
