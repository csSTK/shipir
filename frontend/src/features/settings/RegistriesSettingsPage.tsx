import { useEffect, useId, useRef, useState, type FormEvent, type ReactNode } from 'react'
import { ApiError } from '../../shared/api/client.ts'
import {
  useCreateRegistry,
  useDeleteRegistry,
  useRegistries,
  useTestRegistry,
  useUpdateRegistry,
  type RegistryCredential,
  type RegistryTestStatus,
} from './api.ts'
import './RegistriesSettingsPage.css'

const FIELD_MAX = 255

function errorMessage(error: unknown, fallback: string): string {
  if (error instanceof ApiError) {
    if (error.code === 'REGISTRY_NAME_TAKEN') return 'This name is already in use.'
    if (error.code === 'REGISTRY_HOST_TAKEN') return 'A credential for this registry host already exists.'
    if (error.code === 'INVALID_REGISTRY_HOST') return 'This is not a valid registry host (for example ghcr.io or registry.example.com:5000).'
    if (error.code === 'ENCRYPTION_KEY_MISSING')
      return 'The server has no encryption key. Set SHIPIR_ENCRYPTION_KEY to store registry secrets.'
    if (error.code === 'SECRET_REQUIRED') return 'Enter the password or token.'
    if (error.code === 'REGISTRY_CREDENTIAL_NOT_FOUND') return 'This credential no longer exists.'
    if (error.status === 400 && error.details?.length) return error.details.join(' ')
    if (error.status === 400) return 'Check the entered values.'
  }
  return fallback
}

const TEST_MESSAGES: Record<RegistryTestStatus, { ok: boolean; text: string }> = {
  OK: { ok: true, text: 'Login successful.' },
  AUTH_FAILED: { ok: false, text: 'The registry rejected the username or secret.' },
  UNREACHABLE: { ok: false, text: 'The registry could not be reached.' },
}

function Modal({ title, titleId, onClose, children }: { title: string; titleId: string; onClose: () => void; children: ReactNode }) {
  const ref = useRef<HTMLDivElement>(null)
  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null
    ref.current?.focus()
    return () => previous?.focus()
  }, [])
  return (
    <div className="rg-scrim" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div
        ref={ref}
        className="rg-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        tabIndex={-1}
        onKeyDown={(e) => e.key === 'Escape' && onClose()}
      >
        <h2 id={titleId} className="rg-dialog__title">
          {title}
        </h2>
        {children}
      </div>
    </div>
  )
}

function CredentialDialog({ credential, onClose }: { credential: RegistryCredential | null; onClose: () => void }) {
  const titleId = useId()
  const [name, setName] = useState(credential?.name ?? '')
  const [registryHost, setRegistryHost] = useState(credential?.registryHost ?? '')
  const [username, setUsername] = useState(credential?.username ?? '')
  const [secret, setSecret] = useState('')
  const [problem, setProblem] = useState<string | null>(null)
  const create = useCreateRegistry()
  const update = useUpdateRegistry()
  const pending = create.isPending || update.isPending

  function submit(e: FormEvent) {
    e.preventDefault()
    const n = name.trim()
    const h = registryHost.trim()
    const u = username.trim()
    if (n.length < 1 || n.length > FIELD_MAX) return setProblem(`The name must be 1 to ${FIELD_MAX} characters.`)
    if (h.length < 1 || h.length > FIELD_MAX) return setProblem(`The registry host must be 1 to ${FIELD_MAX} characters.`)
    if (u.length < 1 || u.length > FIELD_MAX) return setProblem(`The username must be 1 to ${FIELD_MAX} characters.`)
    if (!credential && secret.trim() === '') return setProblem('Enter the password or token.')
    setProblem(null)
    const input = { name: n, registryHost: h, username: u, ...(secret.trim() !== '' ? { secret: secret.trim() } : {}) }
    const options = {
      onSuccess: onClose,
      onError: (err: unknown) => setProblem(errorMessage(err, 'Could not save the credential. Try again.')),
    }
    if (credential) update.mutate({ id: credential.id, input }, options)
    else create.mutate(input, options)
  }

  return (
    <Modal title={credential ? 'Edit registry' : 'Add registry'} titleId={titleId} onClose={onClose}>
      <form onSubmit={submit} className="rg-form">
        <label className="rg-field">
          <span>Name</span>
          <input value={name} onChange={(e) => setName(e.target.value)} autoFocus autoComplete="off" />
        </label>
        <label className="rg-field">
          <span>Registry host</span>
          <input
            value={registryHost}
            onChange={(e) => setRegistryHost(e.target.value)}
            autoComplete="off"
            placeholder="ghcr.io"
          />
        </label>
        <label className="rg-field">
          <span>Username</span>
          <input value={username} onChange={(e) => setUsername(e.target.value)} autoComplete="off" />
        </label>
        <label className="rg-field">
          <span>Password or token</span>
          <input
            type="password"
            value={secret}
            onChange={(e) => setSecret(e.target.value)}
            autoComplete="off"
            placeholder={credential ? 'Leave empty to keep the current secret' : ''}
          />
        </label>
        {credential && <small className="rg-muted">Current: {credential.secretHint}</small>}
        {problem && (
          <p role="alert" className="rg-error">
            {problem}
          </p>
        )}
        <div className="rg-actions">
          <button type="button" className="rg-btn" onClick={onClose}>
            Cancel
          </button>
          <button type="submit" className="rg-btn rg-btn--primary" disabled={pending}>
            Save
          </button>
        </div>
      </form>
    </Modal>
  )
}

function TestDialog({ credential, onClose }: { credential: RegistryCredential; onClose: () => void }) {
  const titleId = useId()
  const [problem, setProblem] = useState<string | null>(null)
  const test = useTestRegistry()
  const result = test.isPending ? null : test.data ? TEST_MESSAGES[test.data.status] : null

  function run(e: FormEvent) {
    e.preventDefault()
    setProblem(null)
    test.mutate(credential.id, { onError: (err) => setProblem(errorMessage(err, 'Could not run the test. Try again.')) })
  }

  return (
    <Modal title={`Test ${credential.name}`} titleId={titleId} onClose={onClose}>
      <form onSubmit={run} className="rg-form">
        <p className="rg-text">
          Logs in to <strong>{credential.registryHost}</strong> as {credential.username}.
        </p>
        {test.isPending && <p className="rg-muted">Testing…</p>}
        {result && (
          <p role="status" className={result.ok ? 'rg-ok' : 'rg-error'}>
            {result.text}
          </p>
        )}
        {problem && (
          <p role="alert" className="rg-error">
            {problem}
          </p>
        )}
        <div className="rg-actions">
          <button type="button" className="rg-btn" onClick={onClose}>
            Close
          </button>
          <button type="submit" className="rg-btn rg-btn--primary" disabled={test.isPending}>
            Run test
          </button>
        </div>
      </form>
    </Modal>
  )
}

function DeleteDialog({ credential, onClose }: { credential: RegistryCredential; onClose: () => void }) {
  const titleId = useId()
  const [problem, setProblem] = useState<string | null>(null)
  const remove = useDeleteRegistry()
  return (
    <Modal title="Delete registry" titleId={titleId} onClose={onClose}>
      <p className="rg-text">
        Delete <strong>{credential.name}</strong>? The stored secret is removed and pulls from this registry stop using it.
      </p>
      {problem && (
        <p role="alert" className="rg-error">
          {problem}
        </p>
      )}
      <div className="rg-actions">
        <button type="button" className="rg-btn" onClick={onClose}>
          Cancel
        </button>
        <button
          type="button"
          className="rg-btn rg-btn--danger"
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
  | { kind: 'edit'; credential: RegistryCredential | null }
  | { kind: 'test' | 'delete'; credential: RegistryCredential }
  | null

/** Settings > Registries: ADMIN management of private registry logins. The caller restricts access to ADMIN. */
export function RegistriesSettingsPage() {
  const { data, isPending, isError } = useRegistries()
  const [dialog, setDialog] = useState<DialogState>(null)
  const close = () => setDialog(null)

  return (
    <section className="rg">
      <div className="rg-bar">
        <h2 className="rg-title">Registries</h2>
        <button type="button" className="rg-btn rg-btn--primary" onClick={() => setDialog({ kind: 'edit', credential: null })}>
          Add registry
        </button>
      </div>
      <p className="rg-muted">Logins for pulling images from private registries. Saved secrets are never shown again.</p>
      {isPending && <div aria-busy="true" className="rg-muted">Loading…</div>}
      {isError && <p role="alert" className="rg-error">Could not load registries.</p>}
      {data && data.length === 0 && <p className="rg-muted">No registries yet.</p>}
      {data && data.length > 0 && (
        <ul className="rg-list">
          {data.map((c) => (
            <li key={c.id} className="rg-row">
              <div className="rg-row__main">
                <strong>{c.name}</strong>
                <span className="rg-muted">Host {c.registryHost}</span>
                <span className="rg-muted">User {c.username}</span>
                <span className="rg-muted">Secret {c.secretHint}</span>
              </div>
              <div className="rg-row__actions">
                <button type="button" className="rg-btn" aria-label={`Test ${c.name}`} onClick={() => setDialog({ kind: 'test', credential: c })}>
                  Test
                </button>
                <button type="button" className="rg-btn" aria-label={`Edit ${c.name}`} onClick={() => setDialog({ kind: 'edit', credential: c })}>
                  Edit
                </button>
                <button type="button" className="rg-btn rg-btn--danger-text" aria-label={`Delete ${c.name}`} onClick={() => setDialog({ kind: 'delete', credential: c })}>
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
