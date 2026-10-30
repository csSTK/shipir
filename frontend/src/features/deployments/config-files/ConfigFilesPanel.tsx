import { lazy, Suspense, useId, useState, type CSSProperties } from 'react'
import '../editor/editor.css'
import {
  describeConfigError,
  languageFor,
  useConfigBackups,
  useConfigFile,
  useConfigTree,
  useDeleteConfigFile,
  useRestoreConfigFile,
  useSaveConfigFile,
  type ConfigFile,
  type ConfigFileBackup,
} from './api.ts'
import './ConfigFilesPanel.css'
import { Button, Card, ConfirmDialog } from '../../../shared/ui/index.ts'
import { useTranslation } from 'react-i18next'
import { Trans } from '../../../shared/i18n/Trans.tsx'
import { formatDateTime, formatNumber, ONE_DECIMAL } from '../../../shared/i18n/format.ts'

// Monaco is large: load it only when a file is opened.
const MonacoEditor = lazy(() => import('../editor/MonacoYamlEditor.tsx'))

function formatSize(bytes: number): string {
  return bytes < 1024 ? `${bytes} B` : `${formatNumber(bytes / 1024, ONE_DECIMAL)} KB`
}

interface Props {
  hostId: string
  deploymentId: string
  canWrite: boolean
}

/** Editable files in the deployment directory: tree, Monaco editor with dirty state, backups with restore. */
export function ConfigFilesPanel({ hostId, deploymentId, canWrite }: Props) {
  const { t } = useTranslation('deployments')
  const tree = useConfigTree(hostId, deploymentId)
  const save = useSaveConfigFile(hostId, deploymentId)
  const [selected, setSelected] = useState<string | null>(null)
  const [dirty, setDirty] = useState(false)
  const [pending, setPending] = useState<string | null>(null)
  const [creating, setCreating] = useState(false)
  const [newPath, setNewPath] = useState('')
  const [createError, setCreateError] = useState<string | null>(null)
  const pathId = useId()
  const errorId = useId()

  function choose(path: string) {
    if (path === selected) return
    if (dirty) setPending(path)
    else setSelected(path)
  }

  function create() {
    const path = newPath.trim()
    if (!path) {
      setCreateError(t('ConfigFilesPanel.enterAFilePath'))
      return
    }
    setCreateError(null)
    save.mutate(
      { path, content: '' },
      {
        onSuccess: () => {
          setCreating(false)
          setNewPath('')
          setDirty(false)
          setSelected(path)
        },
        onError: (err) => setCreateError(describeConfigError(err, t('ConfigFilesPanel.couldNotCreateTheFile'))),
      },
    )
  }

  const files = tree.data?.filter((e) => !e.directory) ?? []

  return (
    <Card className="cf sd-panel" role="region" aria-labelledby={`cf-${deploymentId}`}>
      <div className="sd-panel__head">
        <h3 className="sd-panel__title" id={`cf-${deploymentId}`}>
          {t('ConfigFilesPanel.configFiles')}
        </h3>
        {canWrite && !creating && (
          <Button variant="secondary" onClick={() => setCreating(true)}>
            {t('ConfigFilesPanel.newFile')}
          </Button>
        )}
      </div>

      {creating && (
        <form
          className="cf-new"
          onSubmit={(e) => {
            e.preventDefault()
            create()
          }}
        >
          <label htmlFor={pathId}>{t('ConfigFilesPanel.filePath')}</label>
          <input
            id={pathId}
            value={newPath}
            placeholder="config/app.conf"
            onChange={(e) => setNewPath(e.target.value)}
            aria-invalid={createError ? true : undefined}
            aria-describedby={createError ? errorId : undefined}
            autoFocus
            spellCheck={false}
          />
          <Button variant="primary" type="submit" disabled={save.isPending}>
            {t('ConfigFilesPanel.create')}
          </Button>
          <Button variant="ghost"
            
            onClick={() => {
              setCreating(false)
              setCreateError(null)
            }}
>
            {t('ConfigFilesPanel.cancel')}
          </Button>
          {createError && (
            <p id={errorId} role="alert" className="ui-error cf-new__error">
              {createError}
            </p>
          )}
        </form>
      )}

      {tree.isPending ? (
        <p className="sd-muted">{t('ConfigFilesPanel.loadingFiles')}</p>
      ) : tree.error ? (
        <div>
          <p role="alert" className="ui-error">
            {describeConfigError(tree.error, t('ConfigFilesPanel.couldNotLoadTheFiles'))}
          </p>
          <Button variant="secondary" onClick={() => void tree.refetch()}>
            {t('ConfigFilesPanel.retry')}
          </Button>
        </div>
      ) : files.length === 0 ? (
        <p className="sd-muted">{t('ConfigFilesPanel.noConfigFilesYet')}{canWrite ? t('ConfigFilesPanel.createOneWithNewFile') : ''}</p>
      ) : (
        <div className="cf-body">
          <ul className="cf-tree" aria-label={t('ConfigFilesPanel.files')}>
            {tree.data.map((e) => {
              const depth = e.path.split('/').length - 1
              const name = e.path.split('/').pop()
              return (
                <li key={e.path} style={{ '--depth': depth } as CSSProperties}>
                  {e.directory ? (
                    <span className="cf-dir">{name}/</span>
                  ) : (
                    <button type="button" className="cf-file" aria-current={e.path === selected ? 'true' : undefined} title={e.path} onClick={() => choose(e.path)}>
                      {name}
                      {e.readOnly && <span className="cf-ro"> {t('ConfigFilesPanel.readOnly')}</span>}
                    </button>
                  )}
                </li>
              )
            })}
          </ul>
          <div className="cf-main">
            {selected ? (
              <FileView
                key={selected}
                hostId={hostId}
                deploymentId={deploymentId}
                path={selected}
                canWrite={canWrite}
                onDirty={setDirty}
                onDeleted={() => {
                  setDirty(false)
                  setSelected(null)
                }}
              />
            ) : (
              <p className="sd-muted">{t('ConfigFilesPanel.selectAFileToView')}</p>
            )}
          </div>
        </div>
      )}

      {pending && (
        <ConfirmDialog
          title={t('ConfigFilesPanel.discardChanges')}
          confirmLabel={t('ConfigFilesPanel.discard')}
          danger
          onConfirm={() => {
            setDirty(false)
            setSelected(pending)
            setPending(null)
          }}
          open onOpenChange={(open) => { if (!open) setPending(null) }}
        >
          <Trans t={t} i18nKey="ConfigFilesPanel.selectedHasUnsavedChangesDiscard" values={{ selected }} components={{ strong: <strong /> }} />
        </ConfirmDialog>
      )}
    </Card>
  )
}

function FileView({
  hostId,
  deploymentId,
  path,
  canWrite,
  onDirty,
  onDeleted,
}: {
  hostId: string
  deploymentId: string
  path: string
  canWrite: boolean
  onDirty: (dirty: boolean) => void
  onDeleted: () => void
}) {
  const { t } = useTranslation('deployments')
  const file = useConfigFile(hostId, deploymentId, path)
  if (file.isPending) return <p className="sd-muted">{t('ConfigFilesPanel.loadingFile')}</p>
  if (file.error)
    return (
      <div>
        <p role="alert" className="ui-error">
          {describeConfigError(file.error, t('ConfigFilesPanel.couldNotLoadTheFile'))}
        </p>
        <Button variant="secondary" onClick={() => void file.refetch()}>
          {t('ConfigFilesPanel.retry')}
        </Button>
      </div>
    )
  return <FileEditor key={file.data.modifiedAt} hostId={hostId} deploymentId={deploymentId} file={file.data} canWrite={canWrite} onDirty={onDirty} onDeleted={onDeleted} />
}

function FileEditor({
  hostId,
  deploymentId,
  file,
  canWrite,
  onDirty,
  onDeleted,
}: {
  hostId: string
  deploymentId: string
  file: ConfigFile
  canWrite: boolean
  onDirty: (dirty: boolean) => void
  onDeleted: () => void
}) {
  const { t } = useTranslation('deployments')
  const save = useSaveConfigFile(hostId, deploymentId)
  const remove = useDeleteConfigFile(hostId, deploymentId)
  const restore = useRestoreConfigFile(hostId, deploymentId)
  const backups = useConfigBackups(hostId, deploymentId, file.path)
  const [draft, setDraft] = useState(file.content)
  const [problem, setProblem] = useState<string | null>(null)
  const [dialog, setDialog] = useState<'delete' | { backup: ConfigFileBackup } | null>(null)
  const [dialogError, setDialogError] = useState<string | null>(null)

  const readOnly = !canWrite || file.readOnly
  const dirty = draft !== file.content

  function change(value: string) {
    setDraft(value)
    onDirty(value !== file.content)
  }

  function submit() {
    setProblem(null)
    save.mutate(
      { path: file.path, content: draft },
      {
        onSuccess: () => onDirty(false),
        onError: (err) => setProblem(describeConfigError(err, t('ConfigFilesPanel.couldNotSaveTheFile'))),
      },
    )
  }

  function confirmDelete() {
    setDialogError(null)
    remove.mutate(file.path, {
      onSuccess: onDeleted,
      onError: (err) => setDialogError(describeConfigError(err, t('ConfigFilesPanel.couldNotDeleteTheFile'))),
    })
  }

  function confirmRestore(backup: ConfigFileBackup) {
    setDialogError(null)
    restore.mutate(
      { path: file.path, backupId: backup.id },
      {
        onSuccess: () => {
          onDirty(false)
          setDialog(null)
        },
        onError: (err) => setDialogError(describeConfigError(err, t('ConfigFilesPanel.couldNotRestoreTheBackup'))),
      },
    )
  }

  return (
    <div className="cf-file-view">
      <div className="cf-file-head">
        <div>
          <strong>{file.path}</strong>
          <span className="sd-muted"> · {formatSize(file.size)}</span>
          {file.readOnly && <span className="cf-ro"> {t('ConfigFilesPanel.readOnlyGitCheckout')}</span>}
          {dirty && <span className="cf-dirty"> {t('ConfigFilesPanel.unsavedChanges')}</span>}
        </div>
        {!readOnly && (
          <div className="sd-actions">
            <Button variant="primary" onClick={submit} disabled={!dirty || save.isPending}>
              {t('ConfigFilesPanel.save')}
            </Button>
            <Button variant="danger" onClick={() => setDialog('delete')}>
              {t('ConfigFilesPanel.delete')}
            </Button>
          </div>
        )}
      </div>
      {problem && (
        <p role="alert" className="ui-error">
          {problem}
        </p>
      )}
      <div className="ed-yaml__frame cf-editor">
        <Suspense fallback={<div className="ed-yaml__loading" role="status">{t('ConfigFilesPanel.loadingEditor')}</div>}>
          <MonacoEditor
            value={draft}
            onChange={change}
            readOnly={readOnly}
            label={t('ConfigFilesPanel.contentsOfPath', { path: file.path })}
            height="360px"
            language={languageFor(file.path)}
            path={`inmemory://shipir/config/${file.path}`}
          />
        </Suspense>
      </div>

      <h4 className="cf-sub">{t('ConfigFilesPanel.backups')}</h4>
      {backups.isPending ? (
        <p className="sd-muted">{t('ConfigFilesPanel.loadingBackups')}</p>
      ) : backups.error ? (
        <p role="alert" className="ui-error">
          {describeConfigError(backups.error, t('ConfigFilesPanel.couldNotLoadTheBackups'))}
        </p>
      ) : backups.data.length === 0 ? (
        <p className="sd-muted">{t('ConfigFilesPanel.noBackupsYetEachSave')}</p>
      ) : (
        <ul className="cf-backups" aria-label={t('ConfigFilesPanel.backups')}>
          {backups.data.map((b) => (
            <li key={b.id}>
              <span>
                {formatDateTime(b.createdAt)} <span className="sd-muted">· {formatSize(b.size)}</span>
              </span>
              {!readOnly && (
                <Button variant="secondary" aria-label={t('ConfigFilesPanel.restoreBackupFromLocalestring', { date: formatDateTime(b.createdAt) })} onClick={() => setDialog({ backup: b })}>
                  {t('ConfigFilesPanel.restore')}
                </Button>
              )}
            </li>
          ))}
        </ul>
      )}

      {dialog === 'delete' && (
        <ConfirmDialog title={t('ConfigFilesPanel.deleteFile')} confirmLabel={t('ConfigFilesPanel.delete')} danger loading={remove.isPending} error={dialogError} onConfirm={confirmDelete} open onOpenChange={(open) => { if (!open) { setDialog(null); setDialogError(null) } }}>
          <Trans t={t} i18nKey="ConfigFilesPanel.deletePathABackupOf" values={{ path: file.path }} components={{ strong: <strong /> }} />
        </ConfirmDialog>
      )}
      {dialog && dialog !== 'delete' && (
        <ConfirmDialog
          title={t('ConfigFilesPanel.restoreBackup')}
          confirmLabel={t('ConfigFilesPanel.restore')}
          loading={restore.isPending}
          error={dialogError}
          onConfirm={() => confirmRestore(dialog.backup)}
          open onOpenChange={(open) => { if (!open) { setDialog(null); setDialogError(null) } }}
        >
          <Trans t={t} i18nKey="ConfigFilesPanel.replaceTheContentOfPath" values={{ path: file.path }} components={{ strong: <strong /> }} /> {formatDateTime(dialog.backup.createdAt)}{t('ConfigFilesPanel.unsavedChangesAreLost')}
        </ConfirmDialog>
      )}
    </div>
  )
}
