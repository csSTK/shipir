import { useCallback, useEffect, useState } from 'react'
import { ApiError } from '../../shared/api/client.ts'
import { Button, Dialog, Select } from '../../shared/ui/index.ts'
import { useHosts, type Host } from '../hosts/api.ts'
import { useManagedDeployments } from '../deployments/api.ts'
import {
  useSaveSubjectPermissions,
  useSubjectPermissions,
  useUserTeams,
  type ManagedUser,
  type PermissionGrant,
  type PermissionGrantInput,
  type PermissionLevel,
  type Team,
  type UserTeam,
  type UserTeamGrant,
} from './api.ts'
import { useTranslation } from 'react-i18next'
import i18n from 'i18next'
import './SettingsPage.css'
import './UserAccessDialog.css'

type Level = 'NONE' | 'VIEW' | 'OPERATE'
/** Selected level keyed by `h:<hostId>` or `d:<deploymentId>`. */
type Levels = Record<string, Level>

const LABELS: Record<Level, string> = { get NONE() { return i18n.t('settings:UserAccessDialog.none') }, get VIEW() { return i18n.t('settings:UserAccessDialog.view') }, get OPERATE() { return i18n.t('settings:UserAccessDialog.operate') } }

function toLevels(grants: PermissionGrant[]): Levels {
  const out: Levels = {}
  for (const g of grants) {
    if (g.deploymentId) out[`d:${g.deploymentId}`] = g.level
    else if (g.hostId) out[`h:${g.hostId}`] = g.level
  }
  return out
}

function toGrants(levels: Levels, deploymentHosts: Record<string, string>): PermissionGrantInput[] {
  const grants: PermissionGrantInput[] = []
  for (const [key, level] of Object.entries(levels)) {
    if (level === 'NONE') continue
    const id = key.slice(2)
    if (key.startsWith('h:')) grants.push({ hostId: id, level })
    // A host grant already covers its deployments; sending both would be redundant.
    else if ((levels[`h:${deploymentHosts[id]}`] ?? 'NONE') === 'NONE') grants.push({ deploymentId: id, level })
  }
  return grants
}

function errorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    switch (error.code) {
      case 'USER_NOT_FOUND':
        return i18n.t('settings:UserAccessDialog.thisUserNoLongerExists')
      case 'TEAM_NOT_FOUND':
        return i18n.t('settings:UserAccessDialog.thisTeamNoLongerExists')
      case 'UNKNOWN_HOST':
        return i18n.t('settings:UserAccessDialog.anEnvironmentInThisList')
      case 'UNKNOWN_DEPLOYMENT':
        return i18n.t('settings:UserAccessDialog.aDeploymentInThisList')
      case 'ADMIN_NEEDS_NO_GRANTS':
        return i18n.t('settings:UserAccessDialog.adminsHaveAccessToEverything')
      case 'INVALID_GRANT':
      case 'DUPLICATE_GRANT':
        return i18n.t('settings:UserAccessDialog.theseGrantsAreNotValid')
    }
  }
  return i18n.t('settings:UserAccessDialog.couldNotSaveTheAccess')
}

function LevelSelect({ label, value, disabled, onChange }: { label: string; value: Level; disabled?: boolean; onChange: (l: Level) => void }) {
  return (
    <div className="ua-level">
      <Select label={label} hideLabel value={value} disabled={disabled} onChange={(e) => onChange(e.target.value as Level)}>
        {(Object.keys(LABELS) as Level[]).map((l) => (
          <option key={l} value={l}>
            {LABELS[l]}
          </option>
        ))}
      </Select>
    </div>
  )
}

function HostAccess({
  host,
  levels,
  setLevel,
  onDeployments,
}: {
  host: Host
  levels: Levels
  setLevel: (key: string, level: Level) => void
  onDeployments: (ids: string[], hostId: string) => void
}) {
  const { t: tr } = useTranslation('settings')
  const { data, isPending, isError } = useManagedDeployments(host.id)
  const hostLevel = levels[`h:${host.id}`] ?? 'NONE'
  const covered = hostLevel !== 'NONE'
  useEffect(() => {
    if (data) onDeployments(data.map((d) => d.id), host.id)
  }, [data, host.id, onDeployments])
  return (
    <li className="ua-host">
      <div className="ua-line">
        <strong>{host.name}</strong>
        <LevelSelect label={tr('UserAccessDialog.accessToName', { name: host.name })} value={hostLevel} onChange={(l) => setLevel(`h:${host.id}`, l)} />
      </div>
      {isPending && <span className="settings__muted">{tr('UserAccessDialog.loadingDeployments')}</span>}
      {isError && <span className="settings__muted">{tr('UserAccessDialog.couldNotLoadDeployments')}</span>}
      {data && data.length > 0 && (
        <ul className="ua-deployments">
          {data.map((d) => (
            <li key={d.id} className="ua-line">
              <span>
                {d.name}
                {covered && <span className="settings__muted"> {tr('UserAccessDialog.coveredByEnvironmentAccess')}</span>}
              </span>
              <LevelSelect
                label={tr('UserAccessDialog.accessToName', { name: d.name })}
                value={covered ? hostLevel : (levels[`d:${d.id}`] ?? 'NONE')}
                disabled={covered}
                onChange={(l) => setLevel(`d:${d.id}`, l)}
              />
            </li>
          ))}
        </ul>
      )}
    </li>
  )
}

/** Who the access is edited for: one user or one team. */
export type AccessSubject = { kind: 'user'; user: ManagedUser } | { kind: 'team'; team: Team }

const LEVEL_TEXT: Record<PermissionLevel, string> = { get VIEW() { return i18n.t('settings:UserAccessDialog.view') }, get OPERATE() { return i18n.t('settings:UserAccessDialog.operate') } }

function GrantLine({ grant }: { grant: UserTeamGrant }) {
  const { t: tr } = useTranslation('settings')
  if (grant.deploymentId) {
    const name = grant.deploymentName ?? tr('UserAccessDialog.deployment')
    return <li>{tr('UserAccessDialog.nameDeploymentLevelText', { name, LEVEL_TEXT: LEVEL_TEXT[grant.level] })}</li>
  }
  return <li>{`${grant.hostName ?? tr('UserAccessDialog.environment')}: ${LEVEL_TEXT[grant.level]}`}</li>
}

function TeamGrants({ team }: { team: UserTeam }) {
  const { t: tr } = useTranslation('settings')
  return (
    <li>
      <strong>{team.name}</strong>
      {team.grants.length === 0 && <span className="settings__muted"> {tr('UserAccessDialog.noAccessGrantedYet')}</span>}
      {team.grants.length > 0 && (
        <ul>
          {team.grants.map((g) => (
            <GrantLine key={g.deploymentId ?? g.hostId} grant={g} />
          ))}
        </ul>
      )}
    </li>
  )
}

/** Teams the user belongs to with their grants. These add to the direct ones; the effective result is the highest level. */
function InheritedTeams({ user }: { user: ManagedUser }) {
  const { t: tr } = useTranslation('settings')
  const memberOf = useUserTeams(user.id).data?.teams ?? []
  if (memberOf.length === 0) return null
  return (
    <div className="ua-inherited" role="group" aria-label={tr('UserAccessDialog.inheritedTeamAccess')}>
      <p className="settings__muted">
        {tr('UserAccessDialog.alsoInheritsAccessFromThese')}
        {user.role === 'VIEWER' && tr('UserAccessDialog.teamGrantsNeverAllowOperate')}
      </p>
      <ul>
        {memberOf.map((t) => (
          <TeamGrants key={t.id} team={t} />
        ))}
      </ul>
    </div>
  )
}

/** ADMIN dialog: per-host and per-managed-deployment access for one user or team, saved as a full replacement. */
export function UserAccessDialog({ subject, onClose }: { subject: AccessSubject; onClose: () => void }) {
  const { t: tr } = useTranslation('settings')
  const kind = subject.kind
  const target = subject.kind === 'user' ? subject.user : subject.team
  const name = subject.kind === 'user' ? subject.user.username : subject.team.name
  const hosts = useHosts()
  const perms = useSubjectPermissions(kind, target.id)
  const save = useSaveSubjectPermissions()
  const [edited, setEdited] = useState<Levels | null>(null)
  const [deploymentHosts, setDeploymentHosts] = useState<Record<string, string>>({})
  const [problem, setProblem] = useState<string | null>(null)

  const levels = edited ?? (perms.data ? toLevels(perms.data.grants) : {})
  const ready = !!perms.data && !!hosts.data
  const failed = perms.isError || hosts.isError

  const registerDeployments = useCallback(
    (ids: string[], hostId: string) =>
      setDeploymentHosts((prev) => ({ ...prev, ...Object.fromEntries(ids.map((id) => [id, hostId])) })),
    [],
  )

  function setLevel(key: string, level: Level) {
    setEdited({ ...levels, [key]: level })
  }

  function submit() {
    setProblem(null)
    save.mutate(
      { kind, id: target.id, grants: toGrants(levels, deploymentHosts) },
      { onSuccess: onClose, onError: (err) => setProblem(errorMessage(err)) },
    )
  }

  return (
    <Dialog
      open
      onOpenChange={(open) => !open && onClose()}
      title={tr('UserAccessDialog.accessForName', { name })}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            {tr('UserAccessDialog.cancel')}
          </Button>
          <Button variant="primary" disabled={!ready} loading={save.isPending} onClick={submit}>
            {tr('UserAccessDialog.saveAccess')}
          </Button>
        </>
      }
    >
      <p className="settings__muted">
        {tr('UserAccessDialog.chooseWhatNameCanSee', { name })}
        {subject.kind === 'user' && subject.user.role === 'VIEWER' && tr('UserAccessDialog.viewersAreLimitedToView')}
        {subject.kind === 'team' && tr('UserAccessDialog.membersGetThisAccessIn')}
      </p>
      {subject.kind === 'user' && <InheritedTeams user={subject.user} />}
      {!ready && !failed && <p className="settings__muted">{tr('UserAccessDialog.loading')}</p>}
      {failed && <p role="alert" className="settings__error">{tr('UserAccessDialog.couldNotLoadTheAccess')}</p>}
      {ready && hosts.data.length === 0 && <p className="settings__muted">{tr('UserAccessDialog.thereAreNoEnvironmentsYet')}</p>}
      {ready && (
        <ul className="ua-hosts">
          {hosts.data.map((h) => (
            <HostAccess key={h.id} host={h} levels={levels} setLevel={setLevel} onDeployments={registerDeployments} />
          ))}
        </ul>
      )}
      {problem && (
        <p role="alert" className="settings__error">
          {problem}
        </p>
      )}
    </Dialog>
  )
}
