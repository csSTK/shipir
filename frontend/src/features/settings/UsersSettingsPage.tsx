import { Ban, CircleCheck, KeyRound, RotateCcw, Trash2 } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { ApiError } from '../../shared/api/client.ts'
import { Badge, Button, ConfirmDialog, Dialog, ResourceTable, Select, TextField, type ResourceColumn } from '../../shared/ui/index.ts'
import type { RowActions } from '../../shared/ui/ResourceTable.tsx'
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
import { SettingsPage } from './SettingsPage.tsx'
import { UserAccessDialog } from './UserAccessDialog.tsx'
import './SettingsPage.css'
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
    <Dialog
      open
      onOpenChange={(open) => !open && onClose()}
      title="One-time password"
      footer={
        <Button variant="primary" onClick={onClose}>
          Done
        </Button>
      }
    >
      <p className="settings__text">
        Give this password to <strong>{result.username}</strong>. It is shown only once; they must choose their own at the first
        sign-in.
      </p>
      <div className="us-secret">
        <code>{result.oneTimePassword}</code>
        <Button size="sm" onClick={() => void copy()}>
          {copied ? 'Copied' : 'Copy'}
        </Button>
      </div>
    </Dialog>
  )
}

function CreateDialog({ onClose, onCreated }: { onClose: () => void; onCreated: (r: OneTimePassword) => void }) {
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
    <Dialog
      open
      onOpenChange={(open) => !open && onClose()}
      title="Add user"
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button variant="primary" type="submit" form="user-create-form" loading={create.isPending}>
            Create user
          </Button>
        </>
      }
    >
      <form id="user-create-form" onSubmit={submit} className="settings__form">
        <TextField label="Username" value={username} onChange={(e) => setUsername(e.target.value)} autoFocus autoComplete="off" />
        <Select label="Role" value={role} onChange={(e) => setRole(e.target.value as UserRole)}>
          {ROLES.map((r) => (
            <option key={r} value={r}>
              {r}
            </option>
          ))}
        </Select>
        <p className="settings__muted">A one-time password is generated and shown once after creating the user.</p>
        {problem && (
          <p role="alert" className="settings__error">
            {problem}
          </p>
        )}
      </form>
    </Dialog>
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

/** Settings > Users: ADMIN user management. The caller restricts access to ADMIN; `currentUsername` hides self-actions. */
export function UsersSettingsPage({ currentUsername }: { currentUsername: string }) {
  const { data, isPending, isError, refetch } = useUsers()
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

  const columns: ResourceColumn<ManagedUser>[] = [
    {
      id: 'username',
      header: 'Username',
      sortValue: (u) => u.username,
      defaultWidth: 220,
      hideable: false,
      render: (u) => (
        <span className="us-name">
          <strong>{u.username}</strong>
          {u.username === currentUsername && <Badge tone="muted">You</Badge>}
        </span>
      ),
    },
    {
      id: 'role',
      header: 'Role',
      sortValue: (u) => u.role,
      defaultWidth: 160,
      render: (u) => (
        <Select
          label={`Role for ${u.username}`}
          hideLabel
          value={u.role}
          disabled={u.username === currentUsername || changeRole.isPending}
          onChange={(e) => onRole(u, e.target.value as UserRole)}
        >
          {ROLES.map((r) => (
            <option key={r} value={r}>
              {r}
            </option>
          ))}
        </Select>
      ),
    },
    {
      id: 'status',
      header: 'Status',
      sortValue: (u) => (u.disabled ? 1 : 0),
      defaultWidth: 110,
      render: (u) => <Badge tone={u.disabled ? 'bad' : 'ok'}>{u.disabled ? 'Disabled' : 'Active'}</Badge>,
    },
    {
      id: 'password',
      header: 'Password',
      defaultWidth: 220,
      render: (u) =>
        u.passwordChangedAt ? (
          <span className="settings__muted">Password changed {formatDate(u.passwordChangedAt)}</span>
        ) : (
          <Badge tone="warn">Password change pending</Badge>
        ),
    },
  ]

  function rowActions(u: ManagedUser): RowActions {
    const self = u.username === currentUsername
    return {
      primary: [
        ...(u.role !== 'ADMIN' ? [{ label: 'Access for', icon: KeyRound, onSelect: () => open({ kind: 'access', user: u }) }] : []),
        ...(!self
          ? [
              {
                label: u.disabled ? 'Enable' : 'Disable',
                icon: u.disabled ? CircleCheck : Ban,
                disabled: setDisabled.isPending,
                onSelect: () => onToggle(u),
              },
              { label: 'Reset password for', icon: RotateCcw, onSelect: () => open({ kind: 'reset', user: u }) },
              { label: 'Delete', icon: Trash2, danger: true, onSelect: () => open({ kind: 'delete', user: u }) },
            ]
          : []),
      ],
    }
  }

  return (
    <SettingsPage
      wide
      title="Users"
      subtitle="Create accounts, assign roles and disable or remove access. New users choose their own password at the first sign-in."
      actions={
        <Button variant="primary" onClick={() => open({ kind: 'create' })}>
          Add user
        </Button>
      }
    >
      {rowProblem && (
        <p role="alert" className="settings__error">
          {rowProblem}
        </p>
      )}
      <ResourceTable
        label="users"
        columns={columns}
        rows={data}
        rowKey={(u) => u.id}
        searchText={(u) => `${u.username} ${u.role}`}
        defaultSort="username"
        emptyMessage="No users yet."
        isPending={isPending}
        error={isError ? 'Could not load users.' : null}
        onRetry={() => void refetch()}
        rowActions={rowActions}
        rowLabel={(u) => u.username}
      />
      {dialog?.kind === 'create' && <CreateDialog onClose={close} onCreated={(result) => open({ kind: 'password', result })} />}
      {dialog?.kind === 'access' && <UserAccessDialog user={dialog.user} onClose={close} />}
      {dialog?.kind === 'password' && <PasswordDialog result={dialog.result} onClose={close} />}
      {dialog?.kind === 'reset' && (
        <ConfirmDialog
          open
          onOpenChange={(o) => !o && close()}
          title="Reset password"
          confirmLabel="Reset password"
          loading={reset.isPending}
          onConfirm={() =>
            reset.mutate(dialog.user.id, {
              onSuccess: (result) => open({ kind: 'password', result }),
              onError: (err) => setDialogProblem(errorMessage(err, 'Could not reset the password. Try again.')),
            })
          }
        >
          <p className="settings__text">
            Generate a new one-time password for <strong>{dialog.user.username}</strong>? The current password stops working and
            active sessions are ended.
          </p>
          {dialogProblem && (
            <p role="alert" className="settings__error">
              {dialogProblem}
            </p>
          )}
        </ConfirmDialog>
      )}
      {dialog?.kind === 'delete' && (
        <ConfirmDialog
          open
          onOpenChange={(o) => !o && close()}
          title="Delete user"
          confirmLabel="Delete"
          danger
          loading={remove.isPending}
          onConfirm={() =>
            remove.mutate(dialog.user.id, {
              onSuccess: close,
              onError: (err) => setDialogProblem(errorMessage(err, 'Could not delete the user. Try again.')),
            })
          }
        >
          <p className="settings__text">
            Delete <strong>{dialog.user.username}</strong>? This cannot be undone.
          </p>
          {dialogProblem && (
            <p role="alert" className="settings__error">
              {dialogProblem}
            </p>
          )}
        </ConfirmDialog>
      )}
    </SettingsPage>
  )
}
