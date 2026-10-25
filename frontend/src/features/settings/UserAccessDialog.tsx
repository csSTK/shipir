import { useCallback, useEffect, useState } from 'react'
import { useQueries } from '@tanstack/react-query'
import { ApiError, apiFetch } from '../../shared/api/client.ts'
import { Button, Dialog, Select } from '../../shared/ui/index.ts'
import { useHosts, type Host } from '../hosts/api.ts'
import { useManagedDeployments } from '../deployments/api.ts'
import {
  useSaveSubjectPermissions,
  useSubjectPermissions,
  useTeams,
  TEAMS_KEY,
  type ManagedUser,
  type PermissionGrant,
  type PermissionGrantInput,
  type PermissionLevel,
  type TeamDetail,
  type Team,
} from './api.ts'
import './SettingsPage.css'
import './UserAccessDialog.css'

type Level = 'NONE' | 'VIEW' | 'OPERATE'
/** Selected level keyed by `h:<hostId>` or `d:<deploymentId>`. */
type Levels = Record<string, Level>

const LABELS: Record<Level, string> = { NONE: 'None', VIEW: 'View', OPERATE: 'Operate' }

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
        return 'This user no longer exists.'
      case 'TEAM_NOT_FOUND':
        return 'This team no longer exists.'
      case 'UNKNOWN_HOST':
        return 'An environment in this list no longer exists. Close and reopen the dialog.'
      case 'UNKNOWN_DEPLOYMENT':
        return 'A deployment in this list no longer exists. Close and reopen the dialog.'
      case 'ADMIN_NEEDS_NO_GRANTS':
        return 'Admins have access to everything and need no grants.'
      case 'INVALID_GRANT':
      case 'DUPLICATE_GRANT':
        return 'These grants are not valid. Check the selection.'
    }
  }
  return 'Could not save the access. Try again.'
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
        <LevelSelect label={`Access to ${host.name}`} value={hostLevel} onChange={(l) => setLevel(`h:${host.id}`, l)} />
      </div>
      {isPending && <span className="settings__muted">Loading deployments…</span>}
      {isError && <span className="settings__muted">Could not load deployments.</span>}
      {data && data.length > 0 && (
        <ul className="ua-deployments">
          {data.map((d) => (
            <li key={d.id} className="ua-line">
              <span>
                {d.name}
                {covered && <span className="settings__muted"> · covered by environment access</span>}
              </span>
              <LevelSelect
                label={`Access to ${d.name}`}
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

const LEVEL_TEXT: Record<PermissionLevel, string> = { VIEW: 'View', OPERATE: 'Operate' }

function GrantLine({ grant }: { grant: PermissionGrant }) {
  return grant.deploymentId && grant.deploymentHostId ? (
    <DeploymentGrantLine deploymentId={grant.deploymentId} hostId={grant.deploymentHostId} level={grant.level} />
  ) : (
    <HostGrantLine hostId={grant.hostId ?? ''} level={grant.level} />
  )
}

function HostGrantLine({ hostId, level }: { hostId: string; level: PermissionLevel }) {
  const hosts = useHosts()
  return <li>{`${hosts.data?.find((h) => h.id === hostId)?.name ?? 'Environment'}: ${LEVEL_TEXT[level]}`}</li>
}

function DeploymentGrantLine({ deploymentId, hostId, level }: { deploymentId: string; hostId: string; level: PermissionLevel }) {
  const deployments = useManagedDeployments(hostId)
  const name = deployments.data?.find((d) => d.id === deploymentId)?.name ?? 'Deployment'
  return <li>{`${name} (deployment): ${LEVEL_TEXT[level]}`}</li>
}

function TeamGrants({ team }: { team: Team }) {
  const perms = useSubjectPermissions('team', team.id)
  return (
    <li>
      <strong>{team.name}</strong>
      {perms.isError && <span className="settings__muted"> Could not load the team's access.</span>}
      {perms.data && perms.data.grants.length === 0 && <span className="settings__muted"> No access granted yet.</span>}
      {perms.data && perms.data.grants.length > 0 && (
        <ul>
          {perms.data.grants.map((g) => (
            <GrantLine key={g.deploymentId ?? g.hostId} grant={g} />
          ))}
        </ul>
      )}
    </li>
  )
}

/** Teams the user belongs to with their grants. These add to the direct ones; the effective result is the highest level. */
function InheritedTeams({ user }: { user: ManagedUser }) {
  const teams = useTeams()
  const details = useQueries({
    queries: (teams.data ?? []).map((t) => ({
      queryKey: [...TEAMS_KEY, t.id],
      queryFn: () => apiFetch<TeamDetail>(`/teams/${t.id}`),
      gcTime: 0,
    })),
  })
  const memberOf = (teams.data ?? []).filter((_, i) => details[i]?.data?.members.some((m) => m.userId === user.id))
  if (memberOf.length === 0) return null
  return (
    <div className="ua-inherited" role="group" aria-label="Inherited team access">
      <p className="settings__muted">
        Also inherits access from these teams (managed under Teams).
        {user.role === 'VIEWER' && ' Team grants never allow Operate for a viewer; it is capped to View.'}
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
      title={`Access for ${name}`}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button variant="primary" disabled={!ready} loading={save.isPending} onClick={submit}>
            Save access
          </Button>
        </>
      }
    >
      <p className="settings__muted">
        Choose what {name} can see and do per environment. Access to an environment covers all its deployments.
        {subject.kind === 'user' && subject.user.role === 'VIEWER' && ' Viewers are limited to view, even if Operate is selected.'}
        {subject.kind === 'team' && ' Members get this access in addition to their own.'}
      </p>
      {subject.kind === 'user' && <InheritedTeams user={subject.user} />}
      {!ready && !failed && <p className="settings__muted">Loading…</p>}
      {failed && <p role="alert" className="settings__error">Could not load the access settings.</p>}
      {ready && hosts.data.length === 0 && <p className="settings__muted">There are no environments yet.</p>}
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
