import { Ban, CircleCheck, KeyRound, RotateCcw, ShieldOff, Trash2 } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { ApiError } from '../../shared/api/client.ts'
import { Badge, Button, Card, ConfirmDialog, Dialog, ResourceTable, Select, Switch, TextField, type ResourceColumn, type RowActions } from '../../shared/ui/index.ts'
import {
  useChangeUserRole,
  useCreateUser,
  useDeleteUser,
  useResetUserPassword,
  useResetUserTwoFactor,
  useSaveSecuritySettings,
  useSecuritySettings,
  useSetUserDisabled,
  useUsers,
  type ManagedUser,
  type OneTimePassword,
  type UserRole,
} from './api.ts'
import { SettingsPage } from './SettingsPage.tsx'
import { UserAccessDialog } from './UserAccessDialog.tsx'
import { useTranslation } from 'react-i18next'
import { Trans } from '../../shared/i18n/Trans.tsx'
import i18n from 'i18next'
import { formatDateTime } from '../../shared/i18n/format.ts'
import './SettingsPage.css'
import './UsersSettingsPage.css'

const ROLES: UserRole[] = ['ADMIN', 'OPERATOR', 'VIEWER']
const USERNAME_PATTERN = /^[A-Za-z0-9._-]{3,50}$/

function errorMessage(error: unknown, fallback: string): string {
  if (error instanceof ApiError) {
    switch (error.code) {
      case 'USERNAME_TAKEN':
        return i18n.t('settings:UsersSettingsPage.thisUsernameIsAlreadyTaken')
      case 'LAST_ADMIN':
        return i18n.t('settings:UsersSettingsPage.atLeastOneEnabledAdmin')
      case 'USER_HAS_HISTORY':
        return i18n.t('settings:UsersSettingsPage.thisUserHasRecordedActivity')
      case 'USER_NOT_FOUND':
        return i18n.t('settings:UsersSettingsPage.thisUserNoLongerExists')
      case 'SELF_DISABLE':
        return i18n.t('settings:UsersSettingsPage.youCannotDisableYourOwn')
      case 'SELF_DEMOTE':
        return i18n.t('settings:UsersSettingsPage.youCannotChangeYourOwn')
      case 'SELF_DELETE':
        return i18n.t('settings:UsersSettingsPage.youCannotDeleteYourOwn')
      case 'SELF_RESET_2FA':
        return i18n.t('settings:UsersSettingsPage.manageYourOwnTwoFactor')
      case 'SELF_RESET_PASSWORD':
        return i18n.t('settings:UsersSettingsPage.changeYourOwnPasswordUnder')
      case 'EXTERNAL_IDENTITY':
        return i18n.t('settings:UsersSettingsPage.thisUserSignsInViaSso')
    }
    if (error.status === 400 && error.details?.length) return error.details.join(' ')
    if (error.status === 400) return i18n.t('settings:UsersSettingsPage.checkTheEnteredValues')
  }
  return fallback
}

/** Shown once after create or reset; the password cannot be retrieved afterwards. */
function PasswordDialog({ result, onClose }: { result: OneTimePassword; onClose: () => void }) {
  const { t } = useTranslation('settings')
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
      title={t('UsersSettingsPage.oneTimePassword')}
      footer={
        <Button variant="primary" onClick={onClose}>
          {t('UsersSettingsPage.done')}
        </Button>
      }
    >
      <p className="settings__text">
        <Trans t={t} i18nKey="UsersSettingsPage.giveThisPasswordToUsername" values={{ username: result.username }} components={{ strong: <strong /> }} />
      </p>
      <div className="us-secret">
        <code>{result.oneTimePassword}</code>
        <Button size="sm" onClick={() => void copy()}>
          {copied ? t('UsersSettingsPage.copied') : t('UsersSettingsPage.copy')}
        </Button>
      </div>
    </Dialog>
  )
}

function CreateDialog({ onClose, onCreated }: { onClose: () => void; onCreated: (r: OneTimePassword) => void }) {
  const { t } = useTranslation('settings')
  const [username, setUsername] = useState('')
  const [role, setRole] = useState<UserRole>('VIEWER')
  const [problem, setProblem] = useState<string | null>(null)
  const create = useCreateUser()

  function submit(e: FormEvent) {
    e.preventDefault()
    const name = username.trim()
    if (!USERNAME_PATTERN.test(name))
      return setProblem(t('UsersSettingsPage.theUsernameMustBe3'))
    setProblem(null)
    create.mutate(
      { username: name, role },
      {
        onSuccess: onCreated,
        onError: (err) => setProblem(errorMessage(err, t('UsersSettingsPage.couldNotCreateTheUser'))),
      },
    )
  }

  return (
    <Dialog
      open
      onOpenChange={(open) => !open && onClose()}
      title={t('UsersSettingsPage.addUser')}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            {t('UsersSettingsPage.cancel')}
          </Button>
          <Button variant="primary" type="submit" form="user-create-form" loading={create.isPending}>
            {t('UsersSettingsPage.createUser')}
          </Button>
        </>
      }
    >
      <form id="user-create-form" onSubmit={submit} className="settings__form">
        <TextField label={t('UsersSettingsPage.username')} value={username} onChange={(e) => setUsername(e.target.value)} autoFocus autoComplete="off" />
        <Select label={t('UsersSettingsPage.role')} value={role} onChange={(e) => setRole(e.target.value as UserRole)}>
          {ROLES.map((r) => (
            <option key={r} value={r}>
              {r}
            </option>
          ))}
        </Select>
        <p className="settings__muted">{t('UsersSettingsPage.aOneTimePasswordIs')}</p>
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
  | { kind: 'reset' | 'reset2fa' | 'delete' | 'access'; user: ManagedUser }
  | { kind: 'password'; result: OneTimePassword }
  | null

function formatDate(iso: string): string {
  return formatDateTime(iso, { year: 'numeric', month: 'short', day: 'numeric' })
}

/** ADMIN switch: when on, ADMIN accounts must enrol a second factor. */
function RequireTwoFactorCard() {
  const { t } = useTranslation('settings')
  const settings = useSecuritySettings()
  const save = useSaveSecuritySettings()
  return (
    <Card>
      <Switch
        label={t('UsersSettingsPage.require2faForAdmins')}
        help={t('UsersSettingsPage.adminsWithoutTwoFactorAuthentication')}
        checked={settings.data?.require2faForAdmins ?? false}
        disabled={!settings.data || save.isPending}
        onChange={(e) => save.mutate({ require2faForAdmins: e.target.checked })}
      />
      {settings.isError && (
        <p role="alert" className="settings__error">
          {t('UsersSettingsPage.couldNotLoadTheSecurity')}
        </p>
      )}
      {save.isError && (
        <p role="alert" className="settings__error">
          {t('UsersSettingsPage.couldNotSaveTheSecurity')}
        </p>
      )}
    </Card>
  )
}

/** Settings > Users: ADMIN user management. The caller restricts access to ADMIN; `currentUsername` hides self-actions. */
export function UsersSettingsPage({ currentUsername }: { currentUsername: string }) {
  const { t } = useTranslation('settings')
  const { data, isPending, isError, refetch } = useUsers()
  const [dialog, setDialog] = useState<DialogState>(null)
  const [dialogProblem, setDialogProblem] = useState<string | null>(null)
  const [rowProblem, setRowProblem] = useState<string | null>(null)
  const changeRole = useChangeUserRole()
  const setDisabled = useSetUserDisabled()
  const reset = useResetUserPassword()
  const reset2fa = useResetUserTwoFactor()
  const remove = useDeleteUser()

  const open = (d: DialogState) => {
    setDialogProblem(null)
    setDialog(d)
  }
  const close = () => open(null)

  function onRole(u: ManagedUser, role: UserRole) {
    setRowProblem(null)
    changeRole.mutate({ id: u.id, role }, { onError: (err) => setRowProblem(errorMessage(err, t('UsersSettingsPage.couldNotChangeTheRole'))) })
  }

  function onToggle(u: ManagedUser) {
    setRowProblem(null)
    setDisabled.mutate(
      { id: u.id, disabled: !u.disabled },
      { onError: (err) => setRowProblem(errorMessage(err, t('UsersSettingsPage.couldNotUpdateTheAccount'))) },
    )
  }

  const columns: ResourceColumn<ManagedUser>[] = [
    {
      id: 'username',
      header: t('UsersSettingsPage.username'),
      sortValue: (u) => u.username,
      defaultWidth: 220,
      hideable: false,
      render: (u) => (
        <span className="us-name">
          <strong>{u.username}</strong>
          {u.username === currentUsername && <Badge tone="muted">{t('UsersSettingsPage.you')}</Badge>}
        </span>
      ),
    },
    {
      id: 'role',
      header: t('UsersSettingsPage.role'),
      sortValue: (u) => u.role,
      defaultWidth: 160,
      render: (u) => (
        <Select
          label={t('UsersSettingsPage.roleForUsername', { username: u.username })}
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
      header: t('UsersSettingsPage.status'),
      sortValue: (u) => (u.disabled ? 1 : 0),
      // Fits the longest badge label ("Deaktiviert" in German) with cell padding; narrower would clip the pill.
      defaultWidth: 140,
      minWidth: 140,
      render: (u) => <Badge tone={u.disabled ? 'bad' : 'ok'}>{u.disabled ? t('UsersSettingsPage.disabled') : t('UsersSettingsPage.active')}</Badge>,
    },
    {
      id: 'password',
      header: t('UsersSettingsPage.password'),
      defaultWidth: 220,
      render: (u) =>
        u.passwordChangedAt ? (
          <span className="settings__muted">{t('UsersSettingsPage.passwordChangedDate', { date: formatDate(u.passwordChangedAt) })}</span>
        ) : (
          <Badge tone="warn">{t('UsersSettingsPage.passwordChangePending')}</Badge>
        ),
    },
    {
      id: 'twoFactor',
      header: '2FA',
      sortValue: (u) => (u.twoFactorEnabled ? 1 : 0),
      defaultWidth: 100,
      render: (u) => <Badge tone={u.twoFactorEnabled ? 'ok' : 'muted'}>{u.twoFactorEnabled ? t('UsersSettingsPage.on') : t('UsersSettingsPage.off')}</Badge>,
    },
  ]

  function rowActions(u: ManagedUser): RowActions {
    const self = u.username === currentUsername
    return {
      primary: [
        ...(u.role !== 'ADMIN' ? [{ label: t('UsersSettingsPage.accessFor'), icon: KeyRound, onSelect: () => open({ kind: 'access', user: u }) }] : []),
        ...(!self
          ? [
              {
                label: u.disabled ? t('UsersSettingsPage.enable') : t('UsersSettingsPage.disable'),
                icon: u.disabled ? CircleCheck : Ban,
                disabled: setDisabled.isPending,
                onSelect: () => onToggle(u),
              },
              ...(u.twoFactorEnabled
                ? [{ label: t('UsersSettingsPage.reset2faFor'), icon: ShieldOff, onSelect: () => open({ kind: 'reset2fa', user: u }) }]
                : []),
              {
                label: u.externalIdentity ? t('UsersSettingsPage.passwordManagedBySsoFor') : t('UsersSettingsPage.resetPasswordFor'),
                icon: RotateCcw,
                disabled: u.externalIdentity,
                onSelect: () => open({ kind: 'reset', user: u }),
              },
              { label: t('UsersSettingsPage.delete'), icon: Trash2, danger: true, onSelect: () => open({ kind: 'delete', user: u }) },
            ]
          : []),
      ],
    }
  }

  return (
    <SettingsPage
      wide
      title={t('UsersSettingsPage.users')}
      subtitle={t('UsersSettingsPage.createAccountsAssignRolesAnd')}
      actions={
        <Button variant="primary" onClick={() => open({ kind: 'create' })}>
          {t('UsersSettingsPage.addUser')}
        </Button>
      }
    >
      <RequireTwoFactorCard />
      {rowProblem && (
        <p role="alert" className="settings__error">
          {rowProblem}
        </p>
      )}
      <ResourceTable
        label={t('UsersSettingsPage.users2')}
        columns={columns}
        rows={data}
        rowKey={(u) => u.id}
        searchText={(u) => `${u.username} ${u.role}`}
        defaultSort="username"
        emptyMessage={t('UsersSettingsPage.noUsersYet')}
        isPending={isPending}
        error={isError ? t('UsersSettingsPage.couldNotLoadUsers') : null}
        onRetry={() => void refetch()}
        rowActions={rowActions}
        rowLabel={(u) => u.username}
      />
      {dialog?.kind === 'create' && <CreateDialog onClose={close} onCreated={(result) => open({ kind: 'password', result })} />}
      {dialog?.kind === 'access' && <UserAccessDialog subject={{ kind: 'user', user: dialog.user }} onClose={close} />}
      {dialog?.kind === 'password' && <PasswordDialog result={dialog.result} onClose={close} />}
      {dialog?.kind === 'reset' && (
        <ConfirmDialog
          open
          onOpenChange={(o) => !o && close()}
          title={t('UsersSettingsPage.resetPassword')}
          confirmLabel={t('UsersSettingsPage.resetPassword')}
          loading={reset.isPending}
          onConfirm={() =>
            reset.mutate(dialog.user.id, {
              onSuccess: (result) => open({ kind: 'password', result }),
              onError: (err) => setDialogProblem(errorMessage(err, t('UsersSettingsPage.couldNotResetThePassword'))),
            })
          }
        >
          <p className="settings__text">
            <Trans t={t} i18nKey="UsersSettingsPage.generateANewOneTime" values={{ username: dialog.user.username }} components={{ strong: <strong /> }} />
          </p>
          {dialogProblem && (
            <p role="alert" className="settings__error">
              {dialogProblem}
            </p>
          )}
        </ConfirmDialog>
      )}
      {dialog?.kind === 'reset2fa' && (
        <ConfirmDialog
          open
          onOpenChange={(o) => !o && close()}
          title={t('UsersSettingsPage.resetTwoFactorAuthentication')}
          confirmLabel={t('UsersSettingsPage.reset2fa')}
          danger
          loading={reset2fa.isPending}
          onConfirm={() =>
            reset2fa.mutate(dialog.user.id, {
              onSuccess: close,
              onError: (err) => setDialogProblem(errorMessage(err, t('UsersSettingsPage.couldNotResetTwoFactor'))),
            })
          }
        >
          <p className="settings__text">
            <Trans t={t} i18nKey="UsersSettingsPage.removeTwoFactorAuthenticationFor" values={{ username: dialog.user.username }} components={{ strong: <strong /> }} />
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
          title={t('UsersSettingsPage.deleteUser')}
          confirmLabel={t('UsersSettingsPage.delete')}
          danger
          loading={remove.isPending}
          onConfirm={() =>
            remove.mutate(dialog.user.id, {
              onSuccess: close,
              onError: (err) => setDialogProblem(errorMessage(err, t('UsersSettingsPage.couldNotDeleteTheUser'))),
            })
          }
        >
          <p className="settings__text">
            <Trans t={t} i18nKey="UsersSettingsPage.deleteUsernameThisCannotBe" values={{ username: dialog.user.username }} components={{ strong: <strong /> }} />
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
