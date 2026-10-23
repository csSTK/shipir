import { useEffect, useId, useRef, useState, type FormEvent, type ReactNode } from 'react'
import { ApiError } from '../../shared/api/client.ts'
import {
  useCreateGitCredential,
  useDeleteGitCredential,
  useGitCredentials,
  useTestGitCredential,
  useUpdateGitCredential,
  type GitConnectionStatus,
  type GitCredential,
} from './api.ts'
import './GitCredentialsSettingsPage.css'

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

function Modal({ title, titleId, onClose, children }: { title: string; titleId: string; onClose: () => void; children: ReactNode }) {
  const ref = useRef<HTMLDivElement>(null)
  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null
    ref.current?.focus()
    return () => previous?.focus()
  }, [])
  return (
    <div className="gc-scrim" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div
        ref={ref}
        className="gc-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        tabIndex={-1}
        onKeyDown={(e) => e.key === 'Escape' && onClose()}
      >
        <h2 id={titleId} className="gc-dialog__title">
          {title}
        </h2>
        {children}
      </div>
    </div>
  )
}

function CredentialDialog({ credential, onClose }: { credential: GitCredential | null; onClose: () => void }) {
  const titleId = useId()
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
    <Modal title={credential ? 'Edit credential' : 'Add credential'} titleId={titleId} onClose={onClose}>
      <form onSubmit={submit} className="gc-form">
        <label className="gc-field">
          <span>Name</span>
          <input value={name} onChange={(e) => setName(e.target.value)} autoFocus autoComplete="off" />
        </label>
        <label className="gc-field">
          <span>Username</span>
          <input value={username} onChange={(e) => setUsername(e.target.value)} autoComplete="off" />
        </label>
        <label className="gc-field">
          <span>Access token</span>
          <input
            type="password"
            value={token}
            onChange={(e) => setToken(e.target.value)}
            autoComplete="off"
            placeholder={credential ? 'Leave empty to keep the current token' : ''}
          />
        </label>
        {credential && <small className="gc-muted">Current: {credential.tokenHint}</small>}
        {problem && (
          <p role="alert" className="gc-error">
            {problem}
          </p>
        )}
        <div className="gc-actions">
          <button type="button" className="gc-btn" onClick={onClose}>
            Cancel
          </button>
          <button type="submit" className="gc-btn gc-btn--primary" disabled={pending}>
            Save
          </button>
        </div>
      </form>
    </Modal>
  )
}

function TestDialog({ credential, onClose }: { credential: GitCredential; onClose: () => void }) {
  const titleId = useId()
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
    <Modal title={`Test ${credential.name}`} titleId={titleId} onClose={onClose}>
      <form onSubmit={run} className="gc-form">
        <label className="gc-field">
          <span>Repository URL</span>
          <input
            type="url"
            value={repoUrl}
            onChange={(e) => setRepoUrl(e.target.value)}
            autoFocus
            autoComplete="off"
            placeholder="https://github.com/owner/repo.git"
          />
        </label>
        {test.isPending && <p className="gc-muted">Testing…</p>}
        {result && (
          <p role="status" className={result.ok ? 'gc-ok' : 'gc-error'}>
            {result.text}
          </p>
        )}
        {problem && (
          <p role="alert" className="gc-error">
            {problem}
          </p>
        )}
        <div className="gc-actions">
          <button type="button" className="gc-btn" onClick={onClose}>
            Close
          </button>
          <button type="submit" className="gc-btn gc-btn--primary" disabled={test.isPending}>
            Run test
          </button>
        </div>
      </form>
    </Modal>
  )
}

function DeleteDialog({ credential, onClose }: { credential: GitCredential; onClose: () => void }) {
  const titleId = useId()
  const [problem, setProblem] = useState<string | null>(null)
  const remove = useDeleteGitCredential()
  return (
    <Modal title="Delete credential" titleId={titleId} onClose={onClose}>
      <p className="gc-text">
        Delete <strong>{credential.name}</strong>? The stored token is removed.
      </p>
      {problem && (
        <p role="alert" className="gc-error">
          {problem}
        </p>
      )}
      <div className="gc-actions">
        <button type="button" className="gc-btn" onClick={onClose}>
          Cancel
        </button>
        <button
          type="button"
          className="gc-btn gc-btn--danger"
          disabled={remove.isPending}
          onClick={() =>
            remove.mutate(credential.id, {
              onSuccess: onClose,
              onError: (err) => setProblem(errorMessage(err, 'Could not delete the credential. Try again.')),
            })
          }
        >
          Delete
        </button>
      </div>
    </Modal>
  )
}

type DialogState =
  | { kind: 'edit'; credential: GitCredential | null }
  | { kind: 'test' | 'delete'; credential: GitCredential }
  | null

/** Settings > Git credentials: ADMIN management of stored Git access tokens. The caller restricts access to ADMIN. */
export function GitCredentialsSettingsPage() {
  const { data, isPending, isError } = useGitCredentials()
  const [dialog, setDialog] = useState<DialogState>(null)
  const close = () => setDialog(null)

  return (
    <section className="gc">
      <div className="gc-bar">
        <h2 className="gc-title">Git credentials</h2>
        <button type="button" className="gc-btn gc-btn--primary" onClick={() => setDialog({ kind: 'edit', credential: null })}>
          Add credential
        </button>
      </div>
      <p className="gc-muted">Access tokens for deployments deployed from private Git repositories. Saved tokens are never shown again.</p>
      {isPending && <div aria-busy="true" className="gc-muted">Loading…</div>}
      {isError && <p role="alert" className="gc-error">Could not load credentials.</p>}
      {data && data.length === 0 && <p className="gc-muted">No credentials yet.</p>}
      {data && data.length > 0 && (
        <ul className="gc-list">
          {data.map((c) => (
            <li key={c.id} className="gc-row">
              <div className="gc-row__main">
                <strong>{c.name}</strong>
                <span className="gc-muted">User {c.username}</span>
                <span className="gc-muted">Token {c.tokenHint}</span>
              </div>
              <div className="gc-row__actions">
                <button type="button" className="gc-btn" aria-label={`Test ${c.name}`} onClick={() => setDialog({ kind: 'test', credential: c })}>
                  Test
                </button>
                <button type="button" className="gc-btn" aria-label={`Edit ${c.name}`} onClick={() => setDialog({ kind: 'edit', credential: c })}>
                  Edit
                </button>
                <button type="button" className="gc-btn gc-btn--danger-text" aria-label={`Delete ${c.name}`} onClick={() => setDialog({ kind: 'delete', credential: c })}>
                  Delete
                </button>
              </div>
            </li>
          ))}
        </ul>
      )}
      {dialog?.kind === 'edit' && <CredentialDialog credential={dialog.credential} onClose={close} />}
      {dialog?.kind === 'test' && <TestDialog credential={dialog.credential} onClose={close} />}
      {dialog?.kind === 'delete' && <DeleteDialog credential={dialog.credential} onClose={close} />}
    </section>
  )
}
