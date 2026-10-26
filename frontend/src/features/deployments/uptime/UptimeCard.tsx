import { Activity, Pencil, Trash2 } from 'lucide-react'
import { useState } from 'react'
import { ApiError } from '../../../shared/api/client.ts'
import { Badge, Button, Card, ConfirmDialog, EmptyState, IconButton, Skeleton, Switch, type BadgeTone } from '../../../shared/ui/index.ts'
import { useDeleteUptimeCheck, useSaveUptimeCheck, useUptimeChecks, type UptimeCheck, type UptimeState } from './api.ts'
import { UptimeCheckDialog } from './UptimeCheckDialog.tsx'
import './uptime.css'

const STATE: Record<UptimeState, { label: string; tone: BadgeTone }> = {
  UP: { label: 'Up', tone: 'ok' },
  DOWN: { label: 'Down', tone: 'bad' },
  UNKNOWN: { label: 'Unknown', tone: 'muted' },
}

/** Host and path only: query strings and userinfo may carry secrets and are shown in the edit dialog only. */
function displayUrl(raw: string): string {
  try {
    const url = new URL(raw)
    const path = url.pathname === '/' ? '' : url.pathname
    return `${url.host}${path}`
  } catch {
    return ''
  }
}

function RowActions({ hostId, deploymentId, check, onEdit, onDelete }: { hostId: string; deploymentId: string; check: UptimeCheck; onEdit: () => void; onDelete: () => void }) {
  const save = useSaveUptimeCheck(hostId, deploymentId, check.id)
  return (
    <div className="uptime__actions">
      <Switch
        label="Enabled"
        aria-label={`${check.name} enabled`}
        checked={check.enabled}
        disabled={save.isPending}
        onChange={(e) =>
          save.mutate({
            name: check.name,
            url: check.url,
            expectedStatus: check.expectedStatus,
            intervalSeconds: check.intervalSeconds,
            timeoutSeconds: check.timeoutSeconds,
            enabled: e.target.checked,
          })
        }
      />
      <IconButton icon={Pencil} aria-label={`Edit ${check.name}`} onClick={onEdit} />
      <IconButton icon={Trash2} variant="danger" aria-label={`Delete ${check.name}`} onClick={onDelete} />
      {save.isError && (
        <p role="alert" className="ui-error">
          Could not update the check.
        </p>
      )}
    </div>
  )
}

/** HTTP uptime checks of a managed deployment (ADMIN/OPERATOR only). */
export function UptimeCard({ hostId, deploymentId, canOperate }: { hostId: string; deploymentId: string; canOperate: boolean }) {
  const checks = useUptimeChecks(hostId, deploymentId, canOperate)
  const remove = useDeleteUptimeCheck(hostId, deploymentId)
  const [editing, setEditing] = useState<UptimeCheck | 'new' | null>(null)
  const [deleting, setDeleting] = useState<UptimeCheck | null>(null)
  const [deleteError, setDeleteError] = useState<string | null>(null)

  if (!canOperate) return null
  if (checks.error instanceof ApiError && checks.error.status === 403) return null

  const titleId = `uptime-${deploymentId}`
  return (
    <Card className="uptime" role="region" aria-labelledby={titleId}>
      <div className="uptime__head">
        <h3 className="uptime__title" id={titleId}>
          Uptime checks
        </h3>
        {checks.data && checks.data.length > 0 && (
          <Button onClick={() => setEditing('new')}>Add check</Button>
        )}
      </div>
      <p className="uptime__text">Shipir requests these URLs on a schedule and notifies you when one goes down or recovers.</p>

      {checks.isPending && <Skeleton height="var(--space-6)" />}
      {checks.isError && (
        <div className="uptime__error">
          <p role="alert" className="ui-error">
            Could not load the uptime checks.
          </p>
          <Button onClick={() => checks.refetch()}>Retry</Button>
        </div>
      )}
      {checks.data?.length === 0 && (
        <EmptyState
          icon={Activity}
          title="No uptime checks"
          description="Add a check to be notified when this deployment stops answering."
          action={<Button variant="primary" onClick={() => setEditing('new')}>Add check</Button>}
        />
      )}
      {checks.data && checks.data.length > 0 && (
        <ul className="uptime__list">
          {checks.data.map((c) => {
            const state = STATE[c.lastStatus] ?? STATE.UNKNOWN
            const last = c.recentResults[0]
            return (
              <li key={c.id} className="uptime__row">
                <div className="uptime__main">
                  <div className="uptime__line">
                    <Badge tone={state.tone}>{state.label}</Badge>
                    <span className="uptime__name">{c.name}</span>
                  </div>
                  <p className="uptime__meta">
                    {displayUrl(c.url)} · every {c.intervalSeconds} s
                    {c.lastCheckedAt ? ` · checked ${new Date(c.lastCheckedAt).toLocaleString()}` : ' · not checked yet'}
                    {last ? ` · ${last.statusCode ?? last.errorCode ?? 'no response'}` : ''}
                  </p>
                  {last?.errorCode === 'EGRESS_DENIED' && (
                    <p className="uptime__meta">
                      Shipir may not call this address. Private and internal addresses are blocked unless the server sets
                      shipir.egress.allow-private-ranges.
                    </p>
                  )}
                  {c.recentResults.length > 0 && (
                    <span className="uptime__strip" role="img" aria-label={`Last ${c.recentResults.length} results: ${c.recentResults.filter((r) => r.ok).length} ok`}>
                      {[...c.recentResults].reverse().map((r) => (
                        <span key={r.checkedAt} className={`uptime__tick uptime__tick--${r.ok ? 'ok' : 'bad'}`} />
                      ))}
                    </span>
                  )}
                </div>
                <RowActions
                  hostId={hostId}
                  deploymentId={deploymentId}
                  check={c}
                  onEdit={() => setEditing(c)}
                  onDelete={() => {
                    setDeleteError(null)
                    setDeleting(c)
                  }}
                />
              </li>
            )
          })}
        </ul>
      )}

      {editing && (
        <UptimeCheckDialog
          hostId={hostId}
          deploymentId={deploymentId}
          check={editing === 'new' ? null : editing}
          onClose={() => setEditing(null)}
        />
      )}
      {deleting && (
        <ConfirmDialog
          open
          onOpenChange={(open) => !open && setDeleting(null)}
          title="Delete uptime check"
          confirmLabel="Delete"
          danger
          loading={remove.isPending}
          onConfirm={() =>
            remove.mutate(deleting.id, {
              onSuccess: () => setDeleting(null),
              onError: () => setDeleteError('Could not delete the check. Please try again.'),
            })
          }
        >
          <p className="ui-dialog-text">Delete “{deleting.name}” and its results? Notifications for it stop.</p>
          {deleteError && (
            <p role="alert" className="ui-error">
              {deleteError}
            </p>
          )}
        </ConfirmDialog>
      )}
    </Card>
  )
}
