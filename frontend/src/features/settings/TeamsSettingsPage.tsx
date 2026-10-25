import { KeyRound, Pencil, Trash2, Users } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { ApiError } from '../../shared/api/client.ts'
import { Button, ConfirmDialog, Dialog, ResourceTable, TextField, type ResourceColumn } from '../../shared/ui/index.ts'
import type { RowActions } from '../../shared/ui/ResourceTable.tsx'
import {
  useCreateTeam,
  useDeleteTeam,
  useRenameTeam,
  useSaveTeamMembers,
  useTeam,
  useTeams,
  useUsers,
  type Team,
} from './api.ts'
import { SettingsPage } from './SettingsPage.tsx'
import { UserAccessDialog } from './UserAccessDialog.tsx'
import './SettingsPage.css'

function errorMessage(error: unknown, fallback: string): string {
  if (error instanceof ApiError) {
    switch (error.code) {
      case 'TEAM_NAME_TAKEN':
        return 'This team name is already taken.'
      case 'TEAM_NOT_FOUND':
        return 'This team no longer exists.'
      case 'UNKNOWN_USER':
        return 'A selected user no longer exists. Close and reopen the dialog.'
    }
    if (error.status === 400) return 'Enter a team name of up to 100 characters.'
  }
  return fallback
}

function NameDialog({ team, onClose }: { team?: Team; onClose: () => void }) {
  const [name, setName] = useState(team?.name ?? '')
  const [problem, setProblem] = useState<string | null>(null)
  const create = useCreateTeam()
  const rename = useRenameTeam()
  const pending = create.isPending || rename.isPending

  function submit(e: FormEvent) {
    e.preventDefault()
    const value = name.trim()
    if (!value || value.length > 100) return setProblem('Enter a team name of up to 100 characters.')
    setProblem(null)
    const handlers = { onSuccess: onClose, onError: (err: unknown) => setProblem(errorMessage(err, 'Could not save the team. Try again.')) }
    if (team) rename.mutate({ id: team.id, name: value }, handlers)
    else create.mutate(value, handlers)
  }

  return (
    <Dialog
      open
      onOpenChange={(open) => !open && onClose()}
      title={team ? 'Rename team' : 'Add team'}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button variant="primary" type="submit" form="team-name-form" loading={pending}>
            {team ? 'Save' : 'Create team'}
          </Button>
        </>
      }
    >
      <form id="team-name-form" onSubmit={submit} className="settings__form">
        <TextField label="Name" value={name} onChange={(e) => setName(e.target.value)} autoFocus autoComplete="off" />
        {problem && (
          <p role="alert" className="settings__error">
            {problem}
          </p>
        )}
      </form>
    </Dialog>
  )
}

/** Replaces the member list of one team; the checked users are sent as a whole. */
function MembersDialog({ team, onClose }: { team: Team; onClose: () => void }) {
  const detail = useTeam(team.id)
  const users = useUsers()
  const save = useSaveTeamMembers()
  const [edited, setEdited] = useState<string[] | null>(null)
  const [problem, setProblem] = useState<string | null>(null)

  const selected = edited ?? detail.data?.members.map((m) => m.userId) ?? []
  const ready = !!detail.data && !!users.data
  const failed = detail.isError || users.isError

  function toggle(id: string) {
    setEdited(selected.includes(id) ? selected.filter((x) => x !== id) : [...selected, id])
  }

  function submit() {
    setProblem(null)
    save.mutate(
      { id: team.id, userIds: selected },
      { onSuccess: onClose, onError: (err) => setProblem(errorMessage(err, 'Could not save the members. Try again.')) },
    )
  }

  return (
    <Dialog
      open
      onOpenChange={(open) => !open && onClose()}
      title={`Members of ${team.name}`}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button variant="primary" disabled={!ready} loading={save.isPending} onClick={submit}>
            Save members
          </Button>
        </>
      }
    >
      {!ready && !failed && <p className="settings__muted">Loading…</p>}
      {failed && (
        <p role="alert" className="settings__error">
          Could not load the members.
        </p>
      )}
      {ready && (
        <fieldset className="settings__group">
          <legend className="settings__legend">Users in this team</legend>
          {users.data.map((u) => (
            <label key={u.id} className="settings__check">
              <input type="checkbox" checked={selected.includes(u.id)} onChange={() => toggle(u.id)} />
              {u.username}
            </label>
          ))}
        </fieldset>
      )}
      {problem && (
        <p role="alert" className="settings__error">
          {problem}
        </p>
      )}
    </Dialog>
  )
}

type DialogState = { kind: 'create' } | { kind: 'rename' | 'members' | 'access' | 'delete'; team: Team } | null

/** Settings > Teams: ADMIN teams with members and team-level access. The caller restricts access to ADMIN. */
export function TeamsSettingsPage() {
  const { data, isPending, isError, refetch } = useTeams()
  const [dialog, setDialog] = useState<DialogState>(null)
  const [problem, setProblem] = useState<string | null>(null)
  const remove = useDeleteTeam()

  const open = (d: DialogState) => {
    setProblem(null)
    setDialog(d)
  }
  const close = () => open(null)

  const columns: ResourceColumn<Team>[] = [
    { id: 'name', header: 'Name', sortValue: (t) => t.name, defaultWidth: 260, hideable: false, render: (t) => <strong>{t.name}</strong> },
    {
      id: 'members',
      header: 'Members',
      sortValue: (t) => t.memberCount,
      defaultWidth: 120,
      render: (t) => <span className="settings__muted">{t.memberCount}</span>,
    },
  ]

  const rowActions = (t: Team): RowActions => ({
    primary: [
      { label: 'Members of', icon: Users, onSelect: () => open({ kind: 'members', team: t }) },
      { label: 'Access for', icon: KeyRound, onSelect: () => open({ kind: 'access', team: t }) },
      { label: 'Rename', icon: Pencil, onSelect: () => open({ kind: 'rename', team: t }) },
      { label: 'Delete', icon: Trash2, danger: true, onSelect: () => open({ kind: 'delete', team: t }) },
    ],
  })

  return (
    <SettingsPage
      wide
      title="Teams"
      subtitle="Group users and give a team access to environments and deployments. Members get the team's access in addition to their own."
      actions={
        <Button variant="primary" onClick={() => open({ kind: 'create' })}>
          Add team
        </Button>
      }
    >
      <ResourceTable
        label="teams"
        columns={columns}
        rows={data}
        rowKey={(t) => t.id}
        searchText={(t) => t.name}
        defaultSort="name"
        emptyMessage="No teams yet."
        isPending={isPending}
        error={isError ? 'Could not load teams.' : null}
        onRetry={() => void refetch()}
        rowActions={rowActions}
        rowLabel={(t) => t.name}
      />
      {dialog?.kind === 'create' && <NameDialog onClose={close} />}
      {dialog?.kind === 'rename' && <NameDialog team={dialog.team} onClose={close} />}
      {dialog?.kind === 'members' && <MembersDialog team={dialog.team} onClose={close} />}
      {dialog?.kind === 'access' && <UserAccessDialog subject={{ kind: 'team', team: dialog.team }} onClose={close} />}
      {dialog?.kind === 'delete' && (
        <ConfirmDialog
          open
          onOpenChange={(o) => !o && close()}
          title="Delete team"
          confirmLabel="Delete"
          danger
          loading={remove.isPending}
          onConfirm={() =>
            remove.mutate(dialog.team.id, {
              onSuccess: close,
              onError: (err) => setProblem(errorMessage(err, 'Could not delete the team. Try again.')),
            })
          }
        >
          <p className="settings__text">
            Delete <strong>{dialog.team.name}</strong>? Its members lose the access they only had through this team.
          </p>
          {problem && (
            <p role="alert" className="settings__error">
              {problem}
            </p>
          )}
        </ConfirmDialog>
      )}
    </SettingsPage>
  )
}
