import { useId, useState, type FormEvent, type ReactNode } from 'react'
import { ApiError } from '../../shared/api/client.ts'
import {
  useChangeUserRole,
  useCreateUser,
  useDeleteUser,
  useResetUserPassword,
  useSetUserDisabled,
  useUsers,
  type ManagedUser,
  type OneTimePassword,
  type UserRole,
} from './api.ts'
import { Modal } from './UsersModal.tsx'
import { UserAccessDialog } from './UserAccessDialog.tsx'
import './UsersSettingsPage.css'

const ROLES: UserRole[] = ['ADMIN', 'OPERATOR', 'VIEWER']
const USERNAME_PATTERN = /^[A-Za-z0-9._-]{3,50}$/

function errorMessage(error: unknown, fallback: string): string {
  if (error instanceof ApiError) {
    switch (error.code) {
      case 'USERNAME_TAKEN':
        return 'This username is already taken.'
      case 'LAST_ADMIN':
        return 'At least one enabled admin must remain. Promote or enable another admin first.'
      case 'USER_HAS_HISTORY':
        return 'This user has recorded activity and cannot be deleted. Disable the account instead.'
      case 'USER_NOT_FOUND':
        return 'This user no longer exists.'
      case 'SELF_DISABLE':
        return 'You cannot disable your own account.'
      case 'SELF_DEMOTE':
        return 'You cannot change your own role.'
      case 'SELF_DELETE':
        return 'You cannot delete your own account.'
      case 'SELF_RESET_PASSWORD':
        return 'Change your own password under Account instead.'
    }
    if (error.status === 400 && error.details?.length) return error.details.join(' ')
    if (error.status === 400) return 'Check the entered values.'
  }
  return fallback
}

/** Shown once after create or reset; the password cannot be retrieved afterwards. */
function PasswordDialog({ result, onClose }: { result: OneTimePassword; onClose: () => void }) {
  const titleId = useId()
  const [copied, setCopied] = useState(false)
  async function copy() {
    try {
      await navigator.clipboard.writeText(result.oneTimePassword)
      setCopied(true)
    } catch {
      setCopied(false)
    }
  }
  return (
    <Modal title="One-time password" titleId={titleId} onClose={onClose}>
      <p className="us-text">
        Give this password to <strong>{result.username}</strong>. It is shown only once; they must choose their own at the first
        sign-in.
      </p>
      <div className="us-secret">
        <code>{result.oneTimePassword}</code>
        <button type="button" className="us-btn" onClick={() => void copy()}>
          {copied ? 'Copied' : 'Copy'}
        </button>
      </div>
      <div className="us-actions">
        <button type="button" className="us-btn us-btn--primary" onClick={onClose}>
          Done
        </button>
      </div>
    </Modal>
  )
}

function CreateDialog({ onClose, onCreated }: { onClose: () => void; onCreated: (r: OneTimePassword) => void }) {
  const titleId = useId()
  const [username, setUsername] = useState('')
  const [role, setRole] = useState<UserRole>('VIEWER')
  const [problem, setProblem] = useState<string | null>(null)
  const create = useCreateUser()

  function submit(e: FormEvent) {
    e.preventDefault()
    const name = username.trim()
    if (!USERNAME_PATTERN.test(name))
      return setProblem("The username must be 3 to 50 characters: letters, digits, '.', '_' or '-'.")
    setProblem(null)
    create.mutate(
      { username: name, role },
      {
        onSuccess: onCreated,
        onError: (err) => setProblem(errorMessage(err, 'Could not create the user. Try again.')),
      },
    )
  }

  return (
    <Modal title="Add user" titleId={titleId} onClose={onClose}>
      <form onSubmit={submit} className="us-form">
        <label className="us-field">
          <span>Username</span>
          <input value={username} onChange={(e) => setUsername(e.target.value)} autoFocus autoComplete="off" />
        </label>
        <label className="us-field">
          <span>Role</span>
          <select value={role} onChange={(e) => setRole(e.target.value as UserRole)}>
            {ROLES.map((r) => (
              <option key={r} value={r}>
                {r}
              </option>
            ))}
          </select>
        </label>
        <p className="us-muted">A one-time password is generated and shown once after creating the user.</p>
        {problem && (
          <p role="alert" className="us-error">
            {problem}
          </p>
        )}
        <div className="us-actions">
          <button type="button" className="us-btn" onClick={onClose}>
            Cancel
          </button>
          <button type="submit" className="us-btn us-btn--primary" disabled={create.isPending}>
            Create user
          </button>
        </div>
      </form>
    </Modal>
  )
}

function ConfirmDialog(props: {
  title: string
  body: ReactNode
  confirmLabel: string
  danger?: boolean
  pending: boolean
  problem: string | null
  onConfirm: () => void
  onClose: () => void
}) {
  const titleId = useId()
  return (
    <Modal title={props.title} titleId={titleId} onClose={props.onClose}>
      <p className="us-text">{props.body}</p>
      {props.problem && (
        <p role="alert" className="us-error">
          {props.problem}
        </p>
      )}
      <div className="us-actions">
        <button type="button" className="us-btn" onClick={props.onClose}>
          Cancel
        </button>
        <button
          type="button"
          className={`us-btn ${props.danger ? 'us-btn--danger' : 'us-btn--primary'}`}
          disabled={props.pending}
          onClick={props.onConfirm}
        >
          {props.confirmLabel}
        </button>
      </div>
    </Modal>
  )
}

type DialogState =
  | { kind: 'create' }
  | { kind: 'reset' | 'delete' | 'access'; user: ManagedUser }
  | { kind: 'password'; result: OneTimePassword }
  | null

function formatDate(iso: string): string {
  return new Date(iso).toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' })
}

function statusOf(u: ManagedUser): { label: string; tone: 'ok' | 'bad' } {
  if (u.disabled) return { label: 'Disabled', tone: 'bad' }
  return { label: 'Active', tone: 'ok' }
}

/** Settings > Users: ADMIN user management. The caller restricts access to ADMIN; `currentUsername` hides self-actions. */
export function UsersSettingsPage({ currentUsername }: { currentUsername: string }) {
  const { data, isPending, isError } = useUsers()
  const [dialog, setDialog] = useState<DialogState>(null)
  const [dialogProblem, setDialogProblem] = useState<string | null>(null)
  const [rowProblem, setRowProblem] = useState<string | null>(null)
  const changeRole = useChangeUserRole()
  const setDisabled = useSetUserDisabled()
  const reset = useResetUserPassword()
  const remove = useDeleteUser()

  const open = (d: DialogState) => {
    setDialogProblem(null)
    setDialog(d)
  }
  const close = () => open(null)

  function onRole(u: ManagedUser, role: UserRole) {
    setRowProblem(null)
    changeRole.mutate({ id: u.id, role }, { onError: (err) => setRowProblem(errorMessage(err, 'Could not change the role. Try again.')) })
  }

  function onToggle(u: ManagedUser) {
    setRowProblem(null)
    setDisabled.mutate(
      { id: u.id, disabled: !u.disabled },
      { onError: (err) => setRowProblem(errorMessage(err, 'Could not update the account. Try again.')) },
    )
  }

  return (
    <section className="us">
      <div className="us-bar">
        <h2 className="us-title">Users</h2>
        <button type="button" className="us-btn us-btn--primary" onClick={() => open({ kind: 'create' })}>
          Add user
        </button>
      </div>
      <p className="us-muted">Create accounts, assign roles and disable or remove access. New users choose their own password at the first sign-in.</p>
      {rowProblem && (
        <p role="alert" className="us-error">
          {rowProblem}
        </p>
      )}
      {isPending && <div aria-busy="true" className="us-muted">Loading…</div>}
      {isError && <p role="alert" className="us-error">Could not load users.</p>}
      {data && (
        <ul className="us-list">
          {data.map((u) => {
            const self = u.username === currentUsername
            const status = statusOf(u)
            return (
              <li key={u.id} className="us-row">
                <div className="us-row__main">
                  <strong>{u.username}</strong>
                  {self && <span className="us-muted">You</span>}
                  <span className={`us-badge us-badge--${status.tone}`}>{status.label}</span>
                  {u.passwordChangedAt ? (
                    <span className="us-muted">Password changed {formatDate(u.passwordChangedAt)}</span>
                  ) : (
                    <span className="us-badge us-badge--warn">Password change pending</span>
                  )}
                </div>
                <div className="us-row__actions">
                  <select
                    className="us-select"
                    aria-label={`Role for ${u.username}`}
                    value={u.role}
                    disabled={self || changeRole.isPending}
                    onChange={(e) => onRole(u, e.target.value as UserRole)}
                  >
                    {ROLES.map((r) => (
                      <option key={r} value={r}>
                        {r}
                      </option>
                    ))}
                  </select>
                  {u.role !== 'ADMIN' && (
                    <button type="button" className="us-btn" aria-label={`Access for ${u.username}`} onClick={() => open({ kind: 'access', user: u })}>
                      Access
                    </button>
                  )}
                  {!self && (
                    <>
                      <button
                        type="button"
                        className="us-btn"
                        aria-label={`${u.disabled ? 'Enable' : 'Disable'} ${u.username}`}
                        disabled={setDisabled.isPending}
                        onClick={() => onToggle(u)}
                      >
                        {u.disabled ? 'Enable' : 'Disable'}
                      </button>
                      <button type="button" className="us-btn" aria-label={`Reset password for ${u.username}`} onClick={() => open({ kind: 'reset', user: u })}>
                        Reset password
                      </button>
                      <button type="button" className="us-btn us-btn--danger-text" aria-label={`Delete ${u.username}`} onClick={() => open({ kind: 'delete', user: u })}>
                        Delete
                      </button>
                    </>
                  )}
                </div>
              </li>
            )
          })}
        </ul>
      )}
      {dialog?.kind === 'create' && <CreateDialog onClose={close} onCreated={(result) => open({ kind: 'password', result })} />}
      {dialog?.kind === 'access' && <UserAccessDialog user={dialog.user} onClose={close} />}
      {dialog?.kind === 'password' && <PasswordDialog result={dialog.result} onClose={close} />}
      {dialog?.kind === 'reset' && (
        <ConfirmDialog
          title="Reset password"
          body={
            <>
              Generate a new one-time password for <strong>{dialog.user.username}</strong>? The current password stops working and
              active sessions are ended.
            </>
          }
          confirmLabel="Reset password"
          pending={reset.isPending}
          problem={dialogProblem}
          onClose={close}
          onConfirm={() =>
            reset.mutate(dialog.user.id, {
              onSuccess: (result) => open({ kind: 'password', result }),
              onError: (err) => setDialogProblem(errorMessage(err, 'Could not reset the password. Try again.')),
            })
          }
        />
      )}
      {dialog?.kind === 'delete' && (
        <ConfirmDialog
          title="Delete user"
          body={
            <>
              Delete <strong>{dialog.user.username}</strong>? This cannot be undone.
            </>
          }
          confirmLabel="Delete"
          danger
          pending={remove.isPending}
          problem={dialogProblem}
          onClose={close}
          onConfirm={() =>
            remove.mutate(dialog.user.id, {
              onSuccess: close,
              onError: (err) => setDialogProblem(errorMessage(err, 'Could not delete the user. Try again.')),
            })
          }
        />
      )}
    </section>
  )
}
