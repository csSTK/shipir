import { FlaskConical, Pencil, Trash2 } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { ApiError } from '../../shared/api/client.ts'
import { Button, ConfirmDialog, Dialog, ResourceTable, TextField, type ResourceColumn } from '../../shared/ui/index.ts'
import type { RowActions } from '../../shared/ui/ResourceTable.tsx'
import {
  useCreateGitCredential,
  useDeleteGitCredential,
  useGitCredentials,
  useTestGitCredential,
  useUpdateGitCredential,
  type GitConnectionStatus,
  type GitCredential,
} from './api.ts'
import { SettingsPage } from './SettingsPage.tsx'
import './SettingsPage.css'

const FIELD_MAX = 255

function errorMessage(error: unknown, fallback: string): string {
  if (error instanceof ApiError) {
    if (error.code === 'CREDENTIAL_NAME_TAKEN') return 'This name is already in use.'
    if (error.code === 'CREDENTIAL_IN_USE')
      return 'This credential is still used by one or more deployments. Detach it there first.'
    if (error.code === 'ENCRYPTION_KEY_MISSING')
      return 'The server has no encryption key. Set SHIPIR_ENCRYPTION_KEY to store access tokens.'
    if (error.code === 'TOKEN_REQUIRED') return 'Enter the access token.'
    if (error.code === 'INVALID_REPO_URL') return 'This is not a valid https repository URL.'
    if (error.code === 'CREDENTIAL_NOT_FOUND') return 'This credential no longer exists.'
    if (error.status === 409) return 'This name is already in use.'
    if (error.status === 400 && error.details?.length) return error.details.join(' ')
    if (error.status === 400) return 'Check the entered values.'
  }
  return fallback
}

const TEST_MESSAGES: Record<GitConnectionStatus, { ok: boolean; text: string }> = {
  OK: { ok: true, text: 'Connection successful.' },
  AUTH_FAILED: { ok: false, text: 'The server rejected the username or token.' },
  NOT_FOUND: { ok: false, text: 'The repository was not found with these credentials.' },
  UNREACHABLE: { ok: false, text: 'The repository server could not be reached.' },
}

function CredentialDialog({ credential, onClose }: { credential: GitCredential | null; onClose: () => void }) {
  const [name, setName] = useState(credential?.name ?? '')
  const [username, setUsername] = useState(credential?.username ?? '')
  const [token, setToken] = useState('')
  const [problem, setProblem] = useState<string | null>(null)
  const create = useCreateGitCredential()
  const update = useUpdateGitCredential()
  const pending = create.isPending || update.isPending

  function submit(e: FormEvent) {
    e.preventDefault()
    const n = name.trim()
    const u = username.trim()
    if (n.length < 1 || n.length > FIELD_MAX) return setProblem(`The name must be 1 to ${FIELD_MAX} characters.`)
    if (u.length < 1 || u.length > FIELD_MAX) return setProblem(`The username must be 1 to ${FIELD_MAX} characters.`)
    if (!credential && token.trim() === '') return setProblem('Enter the access token.')
    setProblem(null)
    const input = { name: n, username: u, ...(token.trim() !== '' ? { token: token.trim() } : {}) }
    const options = {
      onSuccess: onClose,
      onError: (err: unknown) => setProblem(errorMessage(err, 'Could not save the credential. Try again.')),
    }
    if (credential) update.mutate({ id: credential.id, input }, options)
    else create.mutate(input, options)
  }

  return (
    <Dialog
      open
      onOpenChange={(open) => !open && onClose()}
      title={credential ? 'Edit credential' : 'Add credential'}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button variant="primary" type="submit" form="git-credential-form" loading={pending}>
            Save
          </Button>
        </>
      }
    >
      <form id="git-credential-form" onSubmit={submit} className="settings__form">
        <TextField label="Name" value={name} onChange={(e) => setName(e.target.value)} autoFocus autoComplete="off" />
        <TextField label="Username" value={username} onChange={(e) => setUsername(e.target.value)} autoComplete="off" />
        <TextField
          label="Access token"
          type="password"
          value={token}
          onChange={(e) => setToken(e.target.value)}
          autoComplete="off"
          placeholder={credential ? 'Leave empty to keep the current token' : ''}
          help={credential ? `Current: ${credential.tokenHint}` : undefined}
        />
        {problem && (
          <p role="alert" className="settings__error">
            {problem}
          </p>
        )}
      </form>
    </Dialog>
  )
}

function TestDialog({ credential, onClose }: { credential: GitCredential; onClose: () => void }) {
  const [repoUrl, setRepoUrl] = useState('')
  const [problem, setProblem] = useState<string | null>(null)
  const test = useTestGitCredential()
  const result = test.isPending ? null : test.data ? TEST_MESSAGES[test.data.status] : null

  function run(e: FormEvent) {
    e.preventDefault()
    if (repoUrl.trim() === '') return setProblem('Enter the repository URL.')
    setProblem(null)
    test.mutate(
      { id: credential.id, repoUrl: repoUrl.trim() },
      { onError: (err) => setProblem(errorMessage(err, 'Could not run the test. Try again.')) },
    )
  }

  return (
    <Dialog
      open
      onOpenChange={(open) => !open && onClose()}
      title={`Test ${credential.name}`}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            Close
          </Button>
          <Button variant="primary" type="submit" form="git-test-form" loading={test.isPending}>
            Run test
          </Button>
        </>
      }
    >
      <form id="git-test-form" onSubmit={run} className="settings__form">
        <TextField
          label="Repository URL"
          type="url"
          value={repoUrl}
          onChange={(e) => setRepoUrl(e.target.value)}
          autoFocus
          autoComplete="off"
          placeholder="https://github.com/owner/repo.git"
        />
        {test.isPending && <p className="settings__muted">Testing…</p>}
        {result && (
          <p role="status" className={result.ok ? 'settings__ok' : 'settings__error'}>
            {result.text}
          </p>
        )}
        {problem && (
          <p role="alert" className="settings__error">
            {problem}
          </p>
        )}
      </form>
    </Dialog>
  )
}

function DeleteDialog({ credential, onClose }: { credential: GitCredential; onClose: () => void }) {
  const [problem, setProblem] = useState<string | null>(null)
  const remove = useDeleteGitCredential()
  return (
    <ConfirmDialog
      open
      onOpenChange={(open) => !open && onClose()}
      title="Delete credential"
      confirmLabel="Delete"
      danger
      loading={remove.isPending}
      onConfirm={() =>
        remove.mutate(credential.id, {
          onSuccess: onClose,
          onError: (err) => setProblem(errorMessage(err, 'Could not delete the credential. Try again.')),
        })
      }
    >
      <p className="settings__text">
        Delete <strong>{credential.name}</strong>? The stored token is removed.
      </p>
      {problem && (
        <p role="alert" className="settings__error">
          {problem}
        </p>
      )}
    </ConfirmDialog>
  )
}

type DialogState =
  | { kind: 'edit'; credential: GitCredential | null }
  | { kind: 'test' | 'delete'; credential: GitCredential }
  | null

/** Settings > Git credentials: ADMIN management of stored Git access tokens. The caller restricts access to ADMIN. */
export function GitCredentialsSettingsPage() {
  const { data, isPending, isError, refetch } = useGitCredentials()
  const [dialog, setDialog] = useState<DialogState>(null)
  const close = () => setDialog(null)

  const columns: ResourceColumn<GitCredential>[] = [
    { id: 'name', header: 'Name', sortValue: (c) => c.name, defaultWidth: 220, hideable: false, render: (c) => <strong>{c.name}</strong> },
    { id: 'username', header: 'Username', sortValue: (c) => c.username, defaultWidth: 200, render: (c) => c.username },
    { id: 'token', header: 'Token', defaultWidth: 200, render: (c) => <span className="settings__mono">{c.tokenHint}</span> },
  ]
  const rowActions = (c: GitCredential): RowActions => ({
    primary: [
      { label: 'Test', icon: FlaskConical, onSelect: () => setDialog({ kind: 'test', credential: c }) },
      { label: 'Edit', icon: Pencil, onSelect: () => setDialog({ kind: 'edit', credential: c }) },
      { label: 'Delete', icon: Trash2, danger: true, onSelect: () => setDialog({ kind: 'delete', credential: c }) },
    ],
  })

  return (
    <SettingsPage
      wide
      title="Git credentials"
      subtitle="Access tokens for deployments deployed from private Git repositories. Saved tokens are never shown again."
      actions={
        <Button variant="primary" onClick={() => setDialog({ kind: 'edit', credential: null })}>
          Add credential
        </Button>
      }
    >
      <ResourceTable
        label="credentials"
        columns={columns}
        rows={data}
        rowKey={(c) => c.id}
        searchText={(c) => `${c.name} ${c.username}`}
        defaultSort="name"
        emptyMessage="No credentials yet."
        isPending={isPending}
        error={isError ? 'Could not load credentials.' : null}
        onRetry={() => void refetch()}
        rowActions={rowActions}
        rowLabel={(c) => c.name}
      />
      {dialog?.kind === 'edit' && <CredentialDialog credential={dialog.credential} onClose={close} />}
      {dialog?.kind === 'test' && <TestDialog credential={dialog.credential} onClose={close} />}
      {dialog?.kind === 'delete' && <DeleteDialog credential={dialog.credential} onClose={close} />}
    </SettingsPage>
  )
}
