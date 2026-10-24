import { useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { ApiError } from '../../shared/api/client.ts'
import { Badge } from '../../shared/ui/index.ts'
import { useCurrentUser } from '../auth/index.ts'
import {
  useDeleteDeployment,
  useDeployDeployment,
  useGitRedeploy,
  useManagedDeployment,
  usePullUpdate,
  useRollbackDeployment,
  useDeploymentVersions,
  useStopDeployment,
  type ManagedDeploymentStatus,
  type DeploymentVersion,
} from './api.ts'
import { VersionDiff } from './diff/index.ts'
import { ConfirmDialog } from './ConfirmDialog.tsx'
import { DeploymentRunPanel, type StartedRun } from './DeploymentRunPanel.tsx'
import { GitSourcePanel } from './GitSourcePanel.tsx'
import { ConfigFilesPanel } from './config-files/index.ts'
import { DeploymentContentPanel } from './DeploymentContentPanel.tsx'
import { describeDeploymentError } from './deploymentErrors.ts'
import './DeploymentUi.css'
import './DeploymentDetailPage.css'

const STATUS: Record<ManagedDeploymentStatus, { label: string; tone: 'ok' | 'bad' | 'muted' }> = {
  RUNNING: { label: 'Running', tone: 'ok' },
  STOPPED: { label: 'Stopped', tone: 'muted' },
  ERROR: { label: 'Error', tone: 'bad' },
}

const SOURCE_LABEL = { MANUAL: 'Manual', GIT: 'Git', ROLLBACK: 'Rollback' } as const

function formatDate(iso: string | null): string {
  return iso ? new Date(iso).toLocaleString() : 'Never'
}

type Dialog = { kind: 'delete' } | { kind: 'stop' } | { kind: 'deploy'; draftId: string } | { kind: 'rollback'; version: DeploymentVersion } | null

/** `/environments/:id/deployments/:deploymentId`: status, actions, content and version history of a managed deployment. */
export function DeploymentDetailPage() {
  const { id: hostId = '', deploymentId = '' } = useParams()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const { data: user } = useCurrentUser()
  const canWrite = user?.role === 'ADMIN' || user?.role === 'OPERATOR'

  const deployment = useManagedDeployment(hostId, deploymentId)
  const versions = useDeploymentVersions(hostId, deploymentId)
  const deploy = useDeployDeployment(hostId)
  const redeploy = useGitRedeploy(hostId)
  const pullUpdate = usePullUpdate(hostId)
  const stop = useStopDeployment(hostId)
  const rollback = useRollbackDeployment(hostId)
  const remove = useDeleteDeployment(hostId)

  const [dialog, setDialog] = useState<Dialog>(null)
  const [started, setStarted] = useState<StartedRun | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)
  const [dialogError, setDialogError] = useState<string | null>(null)

  const listPath = `/environments/${hostId}/deployments`
  const closeDialog = () => {
    setDialog(null)
    setDialogError(null)
  }

  if (deployment.isPending) {
    return (
      <section className="sd" aria-busy="true">
        <div role="status" aria-label="Loading deployment" className="sd-skeleton" />
      </section>
    )
  }

  if (deployment.error) {
    const notFound = deployment.error instanceof ApiError && deployment.error.code === 'DEPLOYMENT_NOT_FOUND'
    return (
      <section className="sd">
        <Link className="sd-back" to={listPath}>
          Deployments
        </Link>
        <h2 className="sd-title">{notFound ? 'Deployment not found' : 'Could not load the deployment'}</h2>
        <p role="alert" className="sd-muted">
          {notFound ? 'This deployment does not exist or was deleted.' : describeDeploymentError(deployment.error, 'Something went wrong. Try again.')}
        </p>
        {!notFound && (
          <div>
            <button type="button" className="sk-btn" onClick={() => void deployment.refetch()}>
              Retry
            </button>
          </div>
        )}
      </section>
    )
  }

  const data = deployment.data
  const hasDeployed = versions.data?.some((v) => v.deployed) ?? false
  const busy = deploy.isPending || redeploy.isPending || pullUpdate.isPending || stop.isPending || rollback.isPending

  function startDeploy() {
    setActionError(null)
    deploy.mutate(deploymentId, {
      onSuccess: (d) => setStarted({ kind: 'Deploy', runId: d.runId }),
      onError: (err) => setActionError(describeDeploymentError(err, 'Could not start the deployment. Try again.')),
    })
  }

  function onDeployClick() {
    // A draft that will be snapshotted and a deployed version to compare with: confirm with the diff first.
    const draft = versions.data?.find((v) => !v.deployed)
    if (draft && hasDeployed) setDialog({ kind: 'deploy', draftId: draft.id })
    else startDeploy()
  }

  function confirmDeploy() {
    closeDialog()
    startDeploy()
  }

  function startRedeploy() {
    setActionError(null)
    redeploy.mutate(deploymentId, {
      onSuccess: (d) => setStarted({ kind: 'Pull & redeploy', runId: d.runId }),
      onError: (err) => setActionError(describeDeploymentError(err, 'Could not start the redeployment. Try again.')),
    })
  }

  function startPullUpdate() {
    setActionError(null)
    pullUpdate.mutate(deploymentId, {
      onSuccess: (d) => setStarted({ kind: 'Pull & update', runId: d.runId }),
      onError: (err) => setActionError(describeDeploymentError(err, 'Could not start the update. Try again.')),
    })
  }

  function confirmStop() {
    setDialogError(null)
    stop.mutate(deploymentId, {
      onSuccess: closeDialog,
      onError: (err) => setDialogError(describeDeploymentError(err, 'Could not stop the deployment. Try again.')),
    })
  }

  function confirmRollback(version: DeploymentVersion) {
    setDialogError(null)
    rollback.mutate(
      { deploymentId, versionId: version.id },
      {
        onSuccess: (d) => {
          setStarted({ kind: 'Rollback', runId: d.runId })
          closeDialog()
        },
        onError: (err) => setDialogError(describeDeploymentError(err, 'Could not start the rollback. Try again.')),
      },
    )
  }

  function confirmDelete() {
    setDialogError(null)
    remove.mutate(deploymentId, {
      onSuccess: () => navigate(listPath),
      onError: (err) => setDialogError(describeDeploymentError(err, 'Could not delete the deployment. Try again.')),
    })
  }

  const status = STATUS[data.status]

  return (
    <section className="sd">
      <Link className="sd-back" to={listPath}>
        Deployments
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
            <button type="button" className="sk-btn sk-btn--primary" onClick={onDeployClick} disabled={busy || versions.isPending}>
              Deploy
            </button>
            {data.source === 'GIT' && (
              <button type="button" className="sk-btn" onClick={startRedeploy} disabled={busy}>
                Pull &amp; redeploy
              </button>
            )}
            {data.source === 'FILE' && (
              <button
                type="button"
                className="sk-btn"
                onClick={startPullUpdate}
                disabled={busy || !hasDeployed}
                title={hasDeployed ? 'Pull newer images and recreate the containers' : 'Deploy once first'}
              >
                {pullUpdate.isPending ? 'Updating…' : 'Pull & update'}
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
        <DeploymentRunPanel
          key={started.runId}
          run={started}
          onFinished={() => void queryClient.invalidateQueries({ queryKey: ['hosts', hostId] })}
        />
      )}

      {data.git && <GitSourcePanel key={`${data.git.autoUpdateEnabled}-${data.git.autoUpdateIntervalSeconds}`} deploymentId={deploymentId} hostId={hostId} git={data.git} canWrite={canWrite} />}

      <DeploymentContentPanel deployment={data} hostId={hostId} canWrite={canWrite} />

      <ConfigFilesPanel hostId={hostId} deploymentId={deploymentId} canWrite={canWrite} />

      <div className="sd-panel">
        <h3 className="sd-panel__title">Versions</h3>
        {versions.isPending ? (
          <p className="sd-muted">Loading versions…</p>
        ) : versions.error ? (
          <p role="alert" className="sk-error">
            {describeDeploymentError(versions.error, 'Could not load the versions.')}
          </p>
        ) : versions.data.length === 0 ? (
          <p className="sd-muted">No versions yet. Deploy the deployment to create one.</p>
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
        <ConfirmDialog title="Stop deployment" confirmLabel="Stop" pending={stop.isPending} error={dialogError} onConfirm={confirmStop} onClose={closeDialog}>
          Stop <strong>{data.name}</strong>? Its containers are stopped and removed. The deployment and its versions stay in Shipir.
        </ConfirmDialog>
      )}
      {dialog?.kind === 'deploy' && (
        <ConfirmDialog
          title="Deploy changes"
          confirmLabel="Deploy"
          error={dialogError}
          onConfirm={confirmDeploy}
          onClose={closeDialog}
          details={<VersionDiff hostId={hostId} deploymentId={deploymentId} versionId={dialog.draftId} />}
        >
          Deploy <strong>{data.name}</strong> with these changes? The running containers are replaced.
        </ConfirmDialog>
      )}
      {dialog?.kind === 'delete' && (
        <ConfirmDialog title="Delete deployment" confirmLabel="Delete" danger pending={remove.isPending} error={dialogError} onConfirm={confirmDelete} onClose={closeDialog}>
          Delete <strong>{data.name}</strong>? Its containers are stopped and the deployment with all its versions is removed from Shipir. This cannot be undone.
        </ConfirmDialog>
      )}
      {dialog?.kind === 'rollback' && (
        <ConfirmDialog
          title="Roll back deployment"
          confirmLabel="Roll back"
          pending={rollback.isPending}
          error={dialogError}
          onConfirm={() => confirmRollback(dialog.version)}
          onClose={closeDialog}
          details={<VersionDiff hostId={hostId} deploymentId={deploymentId} versionId={dialog.version.id} />}
        >
          Redeploy <strong>{data.name}</strong> with the version from {formatDate(dialog.version.createdAt)}? The running containers are replaced.
        </ConfirmDialog>
      )}
    </section>
  )
}
