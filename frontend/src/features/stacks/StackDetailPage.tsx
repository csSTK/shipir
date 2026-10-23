import { useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { ApiError } from '../../shared/api/client.ts'
import { Badge } from '../../shared/ui/index.ts'
import { useCurrentUser } from '../auth/index.ts'
import {
  useDeleteStack,
  useDeployStack,
  useGitRedeploy,
  useManagedStack,
  useRollbackStack,
  useStackVersions,
  useStopStack,
  type ManagedStackStatus,
  type StackVersion,
} from './api.ts'
import { ConfirmDialog } from './ConfirmDialog.tsx'
import { DeploymentPanel, type StartedDeployment } from './DeploymentPanel.tsx'
import { GitSourcePanel } from './GitSourcePanel.tsx'
import { StackContentPanel } from './StackContentPanel.tsx'
import { describeStackError } from './stackErrors.ts'
import './StackUi.css'
import './StackDetailPage.css'

const STATUS: Record<ManagedStackStatus, { label: string; tone: 'ok' | 'bad' | 'muted' }> = {
  RUNNING: { label: 'Running', tone: 'ok' },
  STOPPED: { label: 'Stopped', tone: 'muted' },
  ERROR: { label: 'Error', tone: 'bad' },
}

const SOURCE_LABEL = { MANUAL: 'Manual', GIT: 'Git', ROLLBACK: 'Rollback' } as const

function formatDate(iso: string | null): string {
  return iso ? new Date(iso).toLocaleString() : 'Never'
}

type Dialog = { kind: 'delete' } | { kind: 'stop' } | { kind: 'rollback'; version: StackVersion } | null

/** `/environments/:id/stacks/:stackId`: status, actions, content and version history of a managed stack. */
export function StackDetailPage() {
  const { id: hostId = '', stackId = '' } = useParams()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const { data: user } = useCurrentUser()
  const canWrite = user?.role === 'ADMIN' || user?.role === 'OPERATOR'

  const stack = useManagedStack(hostId, stackId)
  const versions = useStackVersions(hostId, stackId)
  const deploy = useDeployStack(hostId)
  const redeploy = useGitRedeploy(hostId)
  const stop = useStopStack(hostId)
  const rollback = useRollbackStack(hostId)
  const remove = useDeleteStack(hostId)

  const [dialog, setDialog] = useState<Dialog>(null)
  const [started, setStarted] = useState<StartedDeployment | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)
  const [dialogError, setDialogError] = useState<string | null>(null)

  const listPath = `/environments/${hostId}/stacks`
  const closeDialog = () => {
    setDialog(null)
    setDialogError(null)
  }

  if (stack.isPending) {
    return (
      <section className="sd" aria-busy="true">
        <div role="status" aria-label="Loading stack" className="sd-skeleton" />
      </section>
    )
  }

  if (stack.error) {
    const notFound = stack.error instanceof ApiError && stack.error.code === 'STACK_NOT_FOUND'
    return (
      <section className="sd">
        <Link className="sd-back" to={listPath}>
          Stacks
        </Link>
        <h2 className="sd-title">{notFound ? 'Stack not found' : 'Could not load the stack'}</h2>
        <p role="alert" className="sd-muted">
          {notFound ? 'This stack does not exist or was deleted.' : describeStackError(stack.error, 'Something went wrong. Try again.')}
        </p>
        {!notFound && (
          <div>
            <button type="button" className="sk-btn" onClick={() => void stack.refetch()}>
              Retry
            </button>
          </div>
        )}
      </section>
    )
  }

  const data = stack.data
  const busy = deploy.isPending || redeploy.isPending || stop.isPending || rollback.isPending

  function startDeploy() {
    setActionError(null)
    deploy.mutate(stackId, {
      onSuccess: (d) => setStarted({ kind: 'Deploy', deploymentId: d.deploymentId }),
      onError: (err) => setActionError(describeStackError(err, 'Could not start the deployment. Try again.')),
    })
  }

  function startRedeploy() {
    setActionError(null)
    redeploy.mutate(stackId, {
      onSuccess: (d) => setStarted({ kind: 'Pull & redeploy', deploymentId: d.deploymentId }),
      onError: (err) => setActionError(describeStackError(err, 'Could not start the redeployment. Try again.')),
    })
  }

  function confirmStop() {
    setDialogError(null)
    stop.mutate(stackId, {
      onSuccess: closeDialog,
      onError: (err) => setDialogError(describeStackError(err, 'Could not stop the stack. Try again.')),
    })
  }

  function confirmRollback(version: StackVersion) {
    setDialogError(null)
    rollback.mutate(
      { stackId, versionId: version.id },
      {
        onSuccess: (d) => {
          setStarted({ kind: 'Rollback', deploymentId: d.deploymentId })
          closeDialog()
        },
        onError: (err) => setDialogError(describeStackError(err, 'Could not start the rollback. Try again.')),
      },
    )
  }

  function confirmDelete() {
    setDialogError(null)
    remove.mutate(stackId, {
      onSuccess: () => navigate(listPath),
      onError: (err) => setDialogError(describeStackError(err, 'Could not delete the stack. Try again.')),
    })
  }

  const status = STATUS[data.status]

  return (
    <section className="sd">
      <Link className="sd-back" to={listPath}>
        Stacks
      </Link>
      <header className="sd-head">
        <div className="sd-head__main">
          <h2 className="sd-title">{data.name}</h2>
          <div className="sd-meta">
            <Badge tone={status.tone}>{status.label}</Badge>
            <span className="sd-muted">{data.source === 'GIT' ? 'From Git' : 'Compose file'}</span>
            <span className="sd-muted">Last deployed: {formatDate(data.lastDeployedAt)}</span>
          </div>
        </div>
        {canWrite && (
          <div className="sd-actions">
            <button type="button" className="sk-btn sk-btn--primary" onClick={startDeploy} disabled={busy}>
              Deploy
            </button>
            {data.source === 'GIT' && (
              <button type="button" className="sk-btn" onClick={startRedeploy} disabled={busy}>
                Pull &amp; redeploy
              </button>
            )}
            <button type="button" className="sk-btn" onClick={() => setDialog({ kind: 'stop' })} disabled={busy}>
              Stop
            </button>
            <button type="button" className="sk-btn sk-btn--plain sk-btn--danger-text" onClick={() => setDialog({ kind: 'delete' })} disabled={busy}>
              Delete
            </button>
          </div>
        )}
      </header>

      {actionError && (
        <p role="alert" className="sk-error">
          {actionError}
        </p>
      )}
      {started && (
        <DeploymentPanel
          key={started.deploymentId}
          deployment={started}
          onFinished={() => void queryClient.invalidateQueries({ queryKey: ['hosts', hostId] })}
        />
      )}

      {data.git && <GitSourcePanel key={`${data.git.autoUpdateEnabled}-${data.git.autoUpdateIntervalSeconds}`} stackId={stackId} hostId={hostId} git={data.git} canWrite={canWrite} />}

      <StackContentPanel stack={data} hostId={hostId} canWrite={canWrite} />

      <div className="sd-panel">
        <h3 className="sd-panel__title">Versions</h3>
        {versions.isPending ? (
          <p className="sd-muted">Loading versions…</p>
        ) : versions.error ? (
          <p role="alert" className="sk-error">
            {describeStackError(versions.error, 'Could not load the versions.')}
          </p>
        ) : versions.data.length === 0 ? (
          <p className="sd-muted">No versions yet. Deploy the stack to create one.</p>
        ) : (
          <ul className="sd-versions" aria-label="Versions">
            {versions.data.map((v) => (
              <li key={v.id} className="sd-version">
                <div className="sd-version__main">
                  <span className="sd-version__source">
                    {SOURCE_LABEL[v.source]}
                    {v.commitSha ? ` · ${v.commitSha.slice(0, 7)}` : ''}
                  </span>
                  <span className="sd-muted">
                    {formatDate(v.createdAt)}
                    {v.createdBy ? ` · ${v.createdBy}` : ''}
                  </span>
                </div>
                <div className="sd-version__tags">
                  {v.current && <Badge tone="ok">Current</Badge>}
                  {!v.deployed && <Badge tone="muted">Draft</Badge>}
                </div>
                {canWrite && v.deployed && !v.current && (
                  <button type="button" className="sk-btn" aria-label={`Roll back to ${formatDate(v.createdAt)}`} onClick={() => setDialog({ kind: 'rollback', version: v })} disabled={busy}>
                    Rollback
                  </button>
                )}
              </li>
            ))}
          </ul>
        )}
      </div>

      {dialog?.kind === 'stop' && (
        <ConfirmDialog title="Stop stack" confirmLabel="Stop" pending={stop.isPending} error={dialogError} onConfirm={confirmStop} onClose={closeDialog}>
          Stop <strong>{data.name}</strong>? Its containers are stopped and removed. The stack and its versions stay in Shipir.
        </ConfirmDialog>
      )}
      {dialog?.kind === 'delete' && (
        <ConfirmDialog title="Delete stack" confirmLabel="Delete" danger pending={remove.isPending} error={dialogError} onConfirm={confirmDelete} onClose={closeDialog}>
          Delete <strong>{data.name}</strong>? Its containers are stopped and the stack with all its versions is removed from Shipir. This cannot be undone.
        </ConfirmDialog>
      )}
      {dialog?.kind === 'rollback' && (
        <ConfirmDialog
          title="Roll back stack"
          confirmLabel="Roll back"
          pending={rollback.isPending}
          error={dialogError}
          onConfirm={() => confirmRollback(dialog.version)}
          onClose={closeDialog}
        >
          Redeploy <strong>{data.name}</strong> with the version from {formatDate(dialog.version.createdAt)}? The running containers are replaced.
        </ConfirmDialog>
      )}
    </section>
  )
}
