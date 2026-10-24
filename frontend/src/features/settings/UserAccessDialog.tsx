import { useCallback, useEffect, useState } from 'react'
import { ApiError } from '../../shared/api/client.ts'
import { Button, Dialog, Select } from '../../shared/ui/index.ts'
import { useHosts, type Host } from '../hosts/api.ts'
import { useManagedDeployments } from '../deployments/api.ts'
import { useSaveUserPermissions, useUserPermissions, type ManagedUser, type PermissionGrant, type PermissionGrantInput } from './api.ts'
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

/** ADMIN dialog: per-host and per-managed-deployment access for one user, saved as a full replacement. */
export function UserAccessDialog({ user, onClose }: { user: ManagedUser; onClose: () => void }) {
  const hosts = useHosts()
  const perms = useUserPermissions(user.id)
  const save = useSaveUserPermissions()
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
      { id: user.id, grants: toGrants(levels, deploymentHosts) },
      { onSuccess: onClose, onError: (err) => setProblem(errorMessage(err)) },
    )
  }

  return (
    <Dialog
      open
      onOpenChange={(open) => !open && onClose()}
      title={`Access for ${user.username}`}
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
        Choose what {user.username} can see and do per environment. Access to an environment covers all its deployments.
        {user.role === 'VIEWER' && ' Viewers are limited to view, even if Operate is selected.'}
      </p>
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
