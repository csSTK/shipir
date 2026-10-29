import { KeyRound, Pencil, Trash2, Users } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { ApiError } from '../../shared/api/client.ts'
import { Checkbox, Button, ConfirmDialog, Dialog, ResourceTable, TextField, type ResourceColumn, type RowActions } from '../../shared/ui/index.ts'
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
import { useTranslation } from 'react-i18next'
import { Trans } from '../../shared/i18n/Trans.tsx'
import i18n from 'i18next'
import './SettingsPage.css'

function errorMessage(error: unknown, fallback: string): string {
  if (error instanceof ApiError) {
    switch (error.code) {
      case 'TEAM_NAME_TAKEN':
        return i18n.t('settings:TeamsSettingsPage.thisTeamNameIsAlready')
      case 'TEAM_NOT_FOUND':
        return i18n.t('settings:TeamsSettingsPage.thisTeamNoLongerExists')
      case 'UNKNOWN_USER':
        return i18n.t('settings:TeamsSettingsPage.aSelectedUserNoLonger')
    }
    if (error.status === 400) return i18n.t('settings:TeamsSettingsPage.enterATeamNameOf')
  }
  return fallback
}

function NameDialog({ team, onClose }: { team?: Team; onClose: () => void }) {
  const { t: tr } = useTranslation('settings')
  const [name, setName] = useState(team?.name ?? '')
  const [problem, setProblem] = useState<string | null>(null)
  const create = useCreateTeam()
  const rename = useRenameTeam()
  const pending = create.isPending || rename.isPending

  function submit(e: FormEvent) {
    e.preventDefault()
    const value = name.trim()
    if (!value || value.length > 100) return setProblem(tr('TeamsSettingsPage.enterATeamNameOf'))
    setProblem(null)
    const handlers = { onSuccess: onClose, onError: (err: unknown) => setProblem(errorMessage(err, tr('TeamsSettingsPage.couldNotSaveTheTeam'))) }
    if (team) rename.mutate({ id: team.id, name: value }, handlers)
    else create.mutate(value, handlers)
  }

  return (
    <Dialog
      open
      onOpenChange={(open) => !open && onClose()}
      title={team ? tr('TeamsSettingsPage.renameTeam') : tr('TeamsSettingsPage.addTeam')}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            {tr('TeamsSettingsPage.cancel')}
          </Button>
          <Button variant="primary" type="submit" form="team-name-form" loading={pending}>
            {team ? tr('TeamsSettingsPage.save') : tr('TeamsSettingsPage.createTeam')}
          </Button>
        </>
      }
    >
      <form id="team-name-form" onSubmit={submit} className="settings__form">
        <TextField label={tr('TeamsSettingsPage.name')} value={name} onChange={(e) => setName(e.target.value)} autoFocus autoComplete="off" />
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
  const { t: tr } = useTranslation('settings')
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
      { onSuccess: onClose, onError: (err) => setProblem(errorMessage(err, tr('TeamsSettingsPage.couldNotSaveTheMembers'))) },
    )
  }

  return (
    <Dialog
      open
      onOpenChange={(open) => !open && onClose()}
      title={tr('TeamsSettingsPage.membersOfName', { name: team.name })}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            {tr('TeamsSettingsPage.cancel')}
          </Button>
          <Button variant="primary" disabled={!ready} loading={save.isPending} onClick={submit}>
            {tr('TeamsSettingsPage.saveMembers')}
          </Button>
        </>
      }
    >
      {!ready && !failed && <p className="settings__muted">{tr('TeamsSettingsPage.loading')}</p>}
      {failed && (
        <p role="alert" className="settings__error">
          {tr('TeamsSettingsPage.couldNotLoadTheMembers')}
        </p>
      )}
      {ready && (
        <fieldset className="settings__group">
          <legend className="settings__legend">{tr('TeamsSettingsPage.usersInThisTeam')}</legend>
          {users.data.map((u) => (
            <Checkbox key={u.id} label={u.username} checked={selected.includes(u.id)} onChange={() => toggle(u.id)} />
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
  const { t: tr } = useTranslation('settings')
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
    { id: 'name', header: tr('TeamsSettingsPage.name'), sortValue: (t) => t.name, defaultWidth: 260, hideable: false, render: (t) => <strong>{t.name}</strong> },
    {
      id: 'members',
      header: tr('TeamsSettingsPage.members'),
      sortValue: (t) => t.memberCount,
      defaultWidth: 120,
      render: (t) => <span className="settings__muted">{t.memberCount}</span>,
    },
  ]

  const rowActions = (t: Team): RowActions => ({
    primary: [
      { label: tr('TeamsSettingsPage.membersOf'), icon: Users, onSelect: () => open({ kind: 'members', team: t }) },
      { label: tr('TeamsSettingsPage.accessFor'), icon: KeyRound, onSelect: () => open({ kind: 'access', team: t }) },
      { label: tr('TeamsSettingsPage.rename'), icon: Pencil, onSelect: () => open({ kind: 'rename', team: t }) },
      { label: tr('TeamsSettingsPage.delete'), icon: Trash2, danger: true, onSelect: () => open({ kind: 'delete', team: t }) },
    ],
  })

  return (
    <SettingsPage
      wide
      title={tr('TeamsSettingsPage.teams')}
      subtitle={tr('TeamsSettingsPage.groupUsersAndGiveA')}
      actions={
        <Button variant="primary" onClick={() => open({ kind: 'create' })}>
          {tr('TeamsSettingsPage.addTeam')}
        </Button>
      }
    >
      <ResourceTable
        label={tr('TeamsSettingsPage.teams2')}
        columns={columns}
        rows={data}
        rowKey={(t) => t.id}
        searchText={(t) => t.name}
        defaultSort="name"
        emptyMessage={tr('TeamsSettingsPage.noTeamsYet')}
        isPending={isPending}
        error={isError ? tr('TeamsSettingsPage.couldNotLoadTeams') : null}
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
          title={tr('TeamsSettingsPage.deleteTeam')}
          confirmLabel={tr('TeamsSettingsPage.delete')}
          danger
          loading={remove.isPending}
          onConfirm={() =>
            remove.mutate(dialog.team.id, {
              onSuccess: close,
              onError: (err) => setProblem(errorMessage(err, tr('TeamsSettingsPage.couldNotDeleteTheTeam'))),
            })
          }
        >
          <p className="settings__text">
            <Trans t={tr} i18nKey="TeamsSettingsPage.deleteNameItsMembersLose" values={{ name: dialog.team.name }} components={{ strong: <strong /> }} />
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
