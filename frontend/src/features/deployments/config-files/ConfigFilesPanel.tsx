import { lazy, Suspense, useId, useState } from 'react'
import { ConfirmDialog } from '../ConfirmDialog.tsx'
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
import { Button, Card } from '../../../shared/ui/index.ts'

// Monaco is large: load it only when a file is opened.
const MonacoEditor = lazy(() => import('../editor/MonacoYamlEditor.tsx'))

function formatSize(bytes: number): string {
  return bytes < 1024 ? `${bytes} B` : `${(bytes / 1024).toFixed(1)} KB`
}

interface Props {
  hostId: string
  deploymentId: string
  canWrite: boolean
}

/** Editable files in the deployment directory: tree, Monaco editor with dirty state, backups with restore. */
export function ConfigFilesPanel({ hostId, deploymentId, canWrite }: Props) {
  const tree = useConfigTree(hostId, deploymentId)
  const save = useSaveConfigFile(hostId, deploymentId)
  const [selected, setSelected] = useState<string | null>(null)
  const [dirty, setDirty] = useState(false)
  const [pending, setPending] = useState<string | null>(null)
  const [creating, setCreating] = useState(false)
  const [newPath, setNewPath] = useState('')
  const [createError, setCreateError] = useState<string | null>(null)
  const pathId = useId()

  function choose(path: string) {
    if (path === selected) return
    if (dirty) setPending(path)
    else setSelected(path)
  }

  function create() {
    const path = newPath.trim()
    if (!path) {
      setCreateError('Enter a file path.')
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
        onError: (err) => setCreateError(describeConfigError(err, 'Could not create the file. Try again.')),
      },
    )
  }

  const files = tree.data?.filter((e) => !e.directory) ?? []

  return (
    <Card className="cf sd-panel" role="region" aria-labelledby={`cf-${deploymentId}`}>
      <div className="sd-panel__head">
        <h3 className="sd-panel__title" id={`cf-${deploymentId}`}>
          Config files
        </h3>
        {canWrite && !creating && (
          <Button variant="secondary" onClick={() => setCreating(true)}>
            New file
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
          <label htmlFor={pathId}>File path</label>
          <input id={pathId} value={newPath} placeholder="config/app.conf" onChange={(e) => setNewPath(e.target.value)} autoFocus spellCheck={false} />
          <Button variant="primary" type="submit" disabled={save.isPending}>
            Create
          </Button>
          <Button variant="ghost"
            
            onClick={() => {
              setCreating(false)
              setCreateError(null)
            }}
>
            Cancel
          </Button>
          {createError && (
            <p role="alert" className="sk-error cf-new__error">
              {createError}
            </p>
          )}
        </form>
      )}

      {tree.isPending ? (
        <p className="sd-muted">Loading files…</p>
      ) : tree.error ? (
        <div>
          <p role="alert" className="sk-error">
            {describeConfigError(tree.error, 'Could not load the files.')}
          </p>
          <Button variant="secondary" onClick={() => void tree.refetch()}>
            Retry
          </Button>
        </div>
      ) : files.length === 0 ? (
        <p className="sd-muted">No config files yet.{canWrite ? ' Create one with New file.' : ''}</p>
      ) : (
        <div className="cf-body">
          <ul className="cf-tree" aria-label="Files">
            {tree.data.map((e) => {
              const depth = e.path.split('/').length - 1
              const name = e.path.split('/').pop()
              return (
                <li key={e.path} style={{ paddingInlineStart: `${depth * 14}px` }}>
                  {e.directory ? (
                    <span className="cf-dir">{name}/</span>
                  ) : (
                    <button type="button" className="cf-file" aria-current={e.path === selected ? 'true' : undefined} title={e.path} onClick={() => choose(e.path)}>
                      {name}
                      {e.readOnly && <span className="cf-ro"> read-only</span>}
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
              <p className="sd-muted">Select a file to view it.</p>
            )}
          </div>
        </div>
      )}

      {pending && (
        <ConfirmDialog
          title="Discard changes"
          confirmLabel="Discard"
          danger
          onConfirm={() => {
            setDirty(false)
            setSelected(pending)
            setPending(null)
          }}
          onClose={() => setPending(null)}
        >
          <strong>{selected}</strong> has unsaved changes. Discard them and open another file?
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
  const file = useConfigFile(hostId, deploymentId, path)
  if (file.isPending) return <p className="sd-muted">Loading file…</p>
  if (file.error)
    return (
      <div>
        <p role="alert" className="sk-error">
          {describeConfigError(file.error, 'Could not load the file.')}
        </p>
        <Button variant="secondary" onClick={() => void file.refetch()}>
          Retry
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
        onError: (err) => setProblem(describeConfigError(err, 'Could not save the file. Try again.')),
      },
    )
  }

  function confirmDelete() {
    setDialogError(null)
    remove.mutate(file.path, {
      onSuccess: onDeleted,
      onError: (err) => setDialogError(describeConfigError(err, 'Could not delete the file. Try again.')),
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
        onError: (err) => setDialogError(describeConfigError(err, 'Could not restore the backup. Try again.')),
      },
    )
  }

  return (
    <div className="cf-file-view">
      <div className="cf-file-head">
        <div>
          <strong>{file.path}</strong>
          <span className="sd-muted"> · {formatSize(file.size)}</span>
          {file.readOnly && <span className="cf-ro"> · read-only (Git checkout)</span>}
          {dirty && <span className="cf-dirty"> · Unsaved changes</span>}
        </div>
        {!readOnly && (
          <div className="sd-actions">
            <Button variant="primary" onClick={submit} disabled={!dirty || save.isPending}>
              Save
            </Button>
            <Button variant="danger" onClick={() => setDialog('delete')}>
              Delete
            </Button>
          </div>
        )}
      </div>
      {problem && (
        <p role="alert" className="sk-error">
          {problem}
        </p>
      )}
      <div className="ed-yaml__frame cf-editor">
        <Suspense fallback={<div className="ed-yaml__loading" role="status">Loading editor…</div>}>
          <MonacoEditor
            value={draft}
            onChange={change}
            readOnly={readOnly}
            label={`Contents of ${file.path}`}
            height="360px"
            language={languageFor(file.path)}
            path={`inmemory://shipir/config/${file.path}`}
          />
        </Suspense>
      </div>

      <h4 className="cf-sub">Backups</h4>
      {backups.isPending ? (
        <p className="sd-muted">Loading backups…</p>
      ) : backups.error ? (
        <p role="alert" className="sk-error">
          {describeConfigError(backups.error, 'Could not load the backups.')}
        </p>
      ) : backups.data.length === 0 ? (
        <p className="sd-muted">No backups yet. Each save keeps the previous content.</p>
      ) : (
        <ul className="cf-backups" aria-label="Backups">
          {backups.data.map((b) => (
            <li key={b.id}>
              <span>
                {new Date(b.createdAt).toLocaleString()} <span className="sd-muted">· {formatSize(b.size)}</span>
              </span>
              {!readOnly && (
                <Button variant="secondary" aria-label={`Restore backup from ${new Date(b.createdAt).toLocaleString()}`} onClick={() => setDialog({ backup: b })}>
                  Restore
                </Button>
              )}
            </li>
          ))}
        </ul>
      )}

      {dialog === 'delete' && (
        <ConfirmDialog title="Delete file" confirmLabel="Delete" danger pending={remove.isPending} error={dialogError} onConfirm={confirmDelete} onClose={() => { setDialog(null); setDialogError(null) }}>
          Delete <strong>{file.path}</strong>? A backup of its content is kept.
        </ConfirmDialog>
      )}
      {dialog && dialog !== 'delete' && (
        <ConfirmDialog
          title="Restore backup"
          confirmLabel="Restore"
          pending={restore.isPending}
          error={dialogError}
          onConfirm={() => confirmRestore(dialog.backup)}
          onClose={() => { setDialog(null); setDialogError(null) }}
        >
          Replace the content of <strong>{file.path}</strong> with the backup from {new Date(dialog.backup.createdAt).toLocaleString()}? Unsaved changes are lost.
        </ConfirmDialog>
      )}
    </div>
  )
}
