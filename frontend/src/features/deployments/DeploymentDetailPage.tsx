import { useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { ApiError } from '../../shared/api/client.ts'
import { Badge, Button, Card, PageHeader, Skeleton } from '../../shared/ui/index.ts'
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
import { ImageAutoUpdatePanel } from './ImageAutoUpdatePanel.tsx'
import { DeploymentLogsPanel } from './logs/DeploymentLogsPanel.tsx'
import { ConfigFilesPanel } from './config-files/index.ts'
import { ServicesCard } from './services/index.ts'
import { UptimeCard } from './uptime/index.ts'
import { WebhookCard } from './webhook/index.ts'
import { DeploymentContentPanel } from './DeploymentContentPanel.tsx'
import { describeDeploymentError } from './deploymentErrors.ts'
import { useTranslation } from 'react-i18next'
import { Trans } from '../../shared/i18n/Trans.tsx'
import i18n from 'i18next'
import { formatDateTime } from '../../shared/i18n/format.ts'
import './DeploymentDetailPage.css'

const STATUS: Record<ManagedDeploymentStatus, { label: string; tone: 'ok' | 'bad' | 'muted' }> = {
  RUNNING: { get label() { return i18n.t('deployments:DeploymentDetailPage.running') }, tone: 'ok' },
  STOPPED: { get label() { return i18n.t('deployments:DeploymentDetailPage.stopped') }, tone: 'muted' },
  ERROR: { get label() { return i18n.t('deployments:DeploymentDetailPage.error') }, tone: 'bad' },
}

const SOURCE_LABEL = { get MANUAL() { return i18n.t('deployments:DeploymentDetailPage.manual') }, get GIT() { return i18n.t('deployments:DeploymentDetailPage.git') }, get ROLLBACK() { return i18n.t('deployments:DeploymentDetailPage.rollback') } } as const

function formatDate(iso: string | null): string {
  return iso ? formatDateTime(iso) : i18n.t('deployments:DeploymentDetailPage.never')
}

type Dialog = { kind: 'delete' } | { kind: 'stop' } | { kind: 'deploy'; draftId: string } | { kind: 'rollback'; version: DeploymentVersion } | null

/** `/environments/:id/deployments/:deploymentId`: status, actions, content and version history of a managed deployment. */
export function DeploymentDetailPage() {
  const { t } = useTranslation('deployments')
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
        <div role="status" aria-label={t('DeploymentDetailPage.loadingDeployment')} className="sd-skeleton">
          <Skeleton height="2rem" width="40%" />
          <Skeleton height="10rem" />
        </div>
      </section>
    )
  }

  if (deployment.error) {
    const notFound = deployment.error instanceof ApiError && deployment.error.code === 'DEPLOYMENT_NOT_FOUND'
    return (
      <section className="sd">
        <Link className="sd-back" to={listPath}>
          {t('DeploymentDetailPage.deployments')}
        </Link>
        <PageHeader title={notFound ? t('DeploymentDetailPage.deploymentNotFound') : t('DeploymentDetailPage.couldNotLoadTheDeployment')} />
        <p role="alert" className="sd-muted">
          {notFound ? t('DeploymentDetailPage.thisDeploymentDoesNotExist') : describeDeploymentError(deployment.error, t('DeploymentDetailPage.somethingWentWrongTryAgain'))}
        </p>
        {!notFound && (
          <div>
            <Button variant="secondary" onClick={() => void deployment.refetch()}>
              {t('DeploymentDetailPage.retry')}
            </Button>
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
      onSuccess: (d) => setStarted({ kind: 'deploy', runId: d.runId }),
      onError: (err) => setActionError(describeDeploymentError(err, t('DeploymentDetailPage.couldNotStartTheDeployment'))),
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
      onSuccess: (d) => setStarted({ kind: 'redeploy', runId: d.runId }),
      onError: (err) => setActionError(describeDeploymentError(err, t('DeploymentDetailPage.couldNotStartTheRedeployment'))),
    })
  }

  function startPullUpdate() {
    setActionError(null)
    pullUpdate.mutate(deploymentId, {
      onSuccess: (d) => setStarted({ kind: 'update', runId: d.runId }),
      onError: (err) => setActionError(describeDeploymentError(err, t('DeploymentDetailPage.couldNotStartTheUpdate'))),
    })
  }

  function confirmStop() {
    setDialogError(null)
    stop.mutate(deploymentId, {
      onSuccess: closeDialog,
      onError: (err) => setDialogError(describeDeploymentError(err, t('DeploymentDetailPage.couldNotStopTheDeployment'))),
    })
  }

  function confirmRollback(version: DeploymentVersion) {
    setDialogError(null)
    rollback.mutate(
      { deploymentId, versionId: version.id },
      {
        onSuccess: (d) => {
          setStarted({ kind: 'rollback', runId: d.runId })
          closeDialog()
        },
        onError: (err) => setDialogError(describeDeploymentError(err, t('DeploymentDetailPage.couldNotStartTheRollback'))),
      },
    )
  }

  function confirmDelete() {
    setDialogError(null)
    remove.mutate(deploymentId, {
      onSuccess: () => navigate(listPath),
      onError: (err) => setDialogError(describeDeploymentError(err, t('DeploymentDetailPage.couldNotDeleteTheDeployment'))),
    })
  }

  const status = STATUS[data.status]

  return (
    <section className="sd">
      <Link className="sd-back" to={listPath}>
        {t('DeploymentDetailPage.deployments')}
      </Link>
      <PageHeader
        title={data.name}
        subtitle={
          <span className="sd-meta">
            <Badge tone={status.tone}>{status.label}</Badge>
            <span>{data.source === 'GIT' ? t('DeploymentDetailPage.fromGit') : t('DeploymentDetailPage.composeFile')}</span>
            <span>{t('DeploymentDetailPage.lastDeployedDate', { date: formatDate(data.lastDeployedAt) })}</span>
          </span>
        }
        actions={
          canWrite ? (
            <div className="sd-actions">
              <Button variant="primary" onClick={onDeployClick} disabled={busy || versions.isPending}>
                {t('DeploymentDetailPage.deploy')}
              </Button>
              {data.source === 'GIT' && (
                <Button variant="secondary" onClick={startRedeploy} disabled={busy}>
                  {t('DeploymentDetailPage.pullRedeploy')}
                </Button>
              )}
              {data.source === 'FILE' && (
                <Button
                  variant="secondary"
                  onClick={startPullUpdate}
                  disabled={busy || !hasDeployed}
                  title={hasDeployed ? t('DeploymentDetailPage.pullNewerImagesAndRecreate') : t('DeploymentDetailPage.deployOnceFirst')}
                >
                  {pullUpdate.isPending ? t('DeploymentDetailPage.updating') : t('DeploymentDetailPage.pullUpdate')}
                </Button>
              )}
              <Button variant="secondary" onClick={() => setDialog({ kind: 'stop' })} disabled={busy}>
                {t('DeploymentDetailPage.stop')}
              </Button>
              <Button variant="danger" onClick={() => setDialog({ kind: 'delete' })} disabled={busy}>
                {t('DeploymentDetailPage.delete')}
              </Button>
            </div>
          ) : undefined
        }
      />

      {actionError && (
        <p role="alert" className="ui-error">
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

      {data.imageAutoUpdate && (
        <ImageAutoUpdatePanel
          key={`${data.imageAutoUpdate.enabled}-${data.imageAutoUpdate.windowStart}-${data.imageAutoUpdate.windowEnd}`}
          deploymentId={deploymentId}
          hostId={hostId}
          info={data.imageAutoUpdate}
          canWrite={canWrite}
        />
      )}

      <DeploymentContentPanel deployment={data} hostId={hostId} canWrite={canWrite} />

      <ServicesCard
        hostId={hostId}
        deploymentId={deploymentId}
        activeProfiles={data.profiles ?? []}
        canOperate={canWrite}
        onRunStarted={(kind, runId) => {
          setActionError(null)
          setStarted({ kind, runId })
        }}
      />

      <DeploymentLogsPanel hostId={hostId} project={data.name} />

      <ConfigFilesPanel hostId={hostId} deploymentId={deploymentId} canWrite={canWrite} />

      <WebhookCard hostId={hostId} deploymentId={deploymentId} canOperate={canWrite} />
      <UptimeCard hostId={hostId} deploymentId={deploymentId} canOperate={canWrite} />

      <Card className="sd-panel">
        <h3 className="sd-panel__title">{t('DeploymentDetailPage.versions')}</h3>
        {versions.isPending ? (
          <p className="sd-muted">{t('DeploymentDetailPage.loadingVersions')}</p>
        ) : versions.error ? (
          <p role="alert" className="ui-error">
            {describeDeploymentError(versions.error, t('DeploymentDetailPage.couldNotLoadTheVersions'))}
          </p>
        ) : versions.data.length === 0 ? (
          <p className="sd-muted">{t('DeploymentDetailPage.noVersionsYetDeployThe')}</p>
        ) : (
          <ul className="sd-versions" aria-label={t('DeploymentDetailPage.versions')}>
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
                  {v.current && <Badge tone="ok">{t('DeploymentDetailPage.current')}</Badge>}
                  {!v.deployed && <Badge tone="muted">{t('DeploymentDetailPage.draft')}</Badge>}
                </div>
                {canWrite && v.deployed && !v.current && (
                  <Button variant="secondary" aria-label={t('DeploymentDetailPage.rollBackToDate', { date: formatDate(v.createdAt) })} onClick={() => setDialog({ kind: 'rollback', version: v })} disabled={busy}>
                    {t('DeploymentDetailPage.rollback')}
                  </Button>
                )}
              </li>
            ))}
          </ul>
        )}
      </Card>

      {dialog?.kind === 'stop' && (
        <ConfirmDialog title={t('DeploymentDetailPage.stopDeployment')} confirmLabel={t('DeploymentDetailPage.stop')} pending={stop.isPending} error={dialogError} onConfirm={confirmStop} onClose={closeDialog}>
          <Trans t={t} i18nKey="DeploymentDetailPage.stopNameItsContainersAre" values={{ name: data.name }} components={{ strong: <strong /> }} />
        </ConfirmDialog>
      )}
      {dialog?.kind === 'deploy' && (
        <ConfirmDialog
          title={t('DeploymentDetailPage.deployChanges')}
          confirmLabel={t('DeploymentDetailPage.deploy')}
          error={dialogError}
          onConfirm={confirmDeploy}
          onClose={closeDialog}
          details={<VersionDiff hostId={hostId} deploymentId={deploymentId} versionId={dialog.draftId} />}
        >
          <Trans t={t} i18nKey="DeploymentDetailPage.deployNameWithTheseChanges" values={{ name: data.name }} components={{ strong: <strong /> }} />
        </ConfirmDialog>
      )}
      {dialog?.kind === 'delete' && (
        <ConfirmDialog title={t('DeploymentDetailPage.deleteDeployment')} confirmLabel={t('DeploymentDetailPage.delete')} danger pending={remove.isPending} error={dialogError} onConfirm={confirmDelete} onClose={closeDialog}>
          <Trans t={t} i18nKey="DeploymentDetailPage.deleteNameItsContainersAre" values={{ name: data.name }} components={{ strong: <strong /> }} />
        </ConfirmDialog>
      )}
      {dialog?.kind === 'rollback' && (
        <ConfirmDialog
          title={t('DeploymentDetailPage.rollBackDeployment')}
          confirmLabel={t('DeploymentDetailPage.rollBack')}
          pending={rollback.isPending}
          error={dialogError}
          onConfirm={() => confirmRollback(dialog.version)}
          onClose={closeDialog}
          details={<VersionDiff hostId={hostId} deploymentId={deploymentId} versionId={dialog.version.id} />}
        >
          <Trans t={t} i18nKey="DeploymentDetailPage.redeployNameWithTheVersion" values={{ name: data.name, date: formatDate(dialog.version.createdAt) }} components={{ strong: <strong /> }} />
        </ConfirmDialog>
      )}
    </section>
  )
}
