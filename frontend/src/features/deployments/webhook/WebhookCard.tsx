import { useState } from 'react'
import { ApiError } from '../../../shared/api/client.ts'
import { Badge, Button, Card, Dialog, Skeleton } from '../../../shared/ui/index.ts'
import { OneTimeSecret } from '../../one-time-secret/index.ts'
import { useDisableWebhook, useIssueWebhook, useWebhookStatus, type WebhookIssued } from './api.ts'
import { useTranslation } from 'react-i18next'
import i18n from 'i18next'
import { formatDateTime } from '../../../shared/i18n/format.ts'
import './webhook.css'

type Confirm = 'rotate' | 'disable' | null

function formatDate(iso: string | null): string {
  return iso ? formatDateTime(iso) : ''
}

function describe(error: unknown, fallback: string): string {
  if (error instanceof ApiError) {
    if (error.code === 'WEBHOOK_ALREADY_ENABLED') return i18n.t('deployments:WebhookCard.theWebhookIsAlreadyEnabled')
    if (error.code === 'WEBHOOK_NOT_FOUND') return i18n.t('deployments:WebhookCard.theWebhookIsNotEnabled')
    if (error.status === 403) return i18n.t('deployments:WebhookCard.youAreNotAllowedTo')
  }
  return fallback
}

/**
 * Redeploy webhook of a managed deployment. The URL contains the secret, so it only ever exists in `issued`
 * (component state) while the one-time reveal is open: never in a list, the route, storage or a log.
 */
export function WebhookCard({ hostId, deploymentId, canOperate }: { hostId: string; deploymentId: string; canOperate: boolean }) {
  const { t } = useTranslation('deployments')
  const status = useWebhookStatus(hostId, deploymentId, canOperate)
  const enable = useIssueWebhook(hostId, deploymentId, 'enable')
  const rotate = useIssueWebhook(hostId, deploymentId, 'rotate')
  const disable = useDisableWebhook(hostId, deploymentId)
  const [issued, setIssued] = useState<WebhookIssued | null>(null)
  const [confirm, setConfirm] = useState<Confirm>(null)
  const [message, setMessage] = useState<string | null>(null)
  const [dialogError, setDialogError] = useState<string | null>(null)

  if (!canOperate) return null
  if (status.error instanceof ApiError && status.error.status === 403) return null

  const url = issued ? `${window.location.origin}${issued.path}` : ''
  const closeConfirm = () => {
    setConfirm(null)
    setDialogError(null)
  }

  function onEnable() {
    setMessage(null)
    enable.mutate(undefined, {
      onSuccess: (result) => {
        setIssued(result)
        enable.reset() // the secret lives only in `issued`, not in the mutation observer
      },
      onError: (err) => setMessage(describe(err, t('WebhookCard.couldNotEnableTheWebhook'))),
    })
  }

  function onRotate() {
    setDialogError(null)
    rotate.mutate(undefined, {
      onSuccess: (result) => {
        closeConfirm()
        setIssued(result)
        rotate.reset()
      },
      onError: (err) => setDialogError(describe(err, t('WebhookCard.couldNotRotateTheWebhook'))),
    })
  }

  function onDisable() {
    setDialogError(null)
    disable.mutate(undefined, {
      onSuccess: closeConfirm,
      onError: (err) => setDialogError(describe(err, t('WebhookCard.couldNotDisableTheWebhook'))),
    })
  }

  const enabled = status.data?.enabled === true

  return (
    <Card className="webhook" role="region" aria-labelledby={`webhook-${deploymentId}`}>
      <div className="webhook__head">
        <h3 className="webhook__title" id={`webhook-${deploymentId}`}>
          {t('WebhookCard.redeployWebhook')}
        </h3>
        {status.data && <Badge tone={enabled ? 'ok' : 'muted'}>{enabled ? t('WebhookCard.enabled') : t('WebhookCard.disabled')}</Badge>}
      </div>
      <p className="webhook__text">
        {t('WebhookCard.aPostToTheWebhook')}
      </p>

      {status.isPending && <Skeleton height="var(--space-6)" />}
      {status.isError && (
        <p role="alert" className="ui-error">
          {t('WebhookCard.couldNotLoadTheWebhook')}
        </p>
      )}
      {status.data && enabled && (
        <p className="webhook__meta">
          {t('WebhookCard.enabledDate', { date: formatDate(status.data.createdAt) })}
          {status.data.rotatedAt ? t('WebhookCard.rotatedDate', { date: formatDate(status.data.rotatedAt) }) : ''}
        </p>
      )}
      {message && (
        <p role="alert" className="ui-error">
          {message}
        </p>
      )}
      {status.data && (
        <div className="webhook__actions">
          {enabled ? (
            <>
              <Button onClick={() => setConfirm('rotate')}>{t('WebhookCard.rotate')}</Button>
              <Button variant="danger" onClick={() => setConfirm('disable')}>
                {t('WebhookCard.disable')}
              </Button>
            </>
          ) : (
            <Button variant="primary" onClick={onEnable} loading={enable.isPending}>
              {t('WebhookCard.enableWebhook')}
            </Button>
          )}
        </div>
      )}

      {confirm === 'rotate' && (
        <Dialog
          open
          onOpenChange={(open) => !open && closeConfirm()}
          title={t('WebhookCard.rotateWebhook')}
          footer={
            <>
              <Button variant="ghost" onClick={closeConfirm}>
                {t('WebhookCard.cancel')}
              </Button>
              <Button variant="primary" loading={rotate.isPending} onClick={onRotate}>
                {t('WebhookCard.rotate')}
              </Button>
            </>
          }
        >
          <p className="ui-dialog-text">{t('WebhookCard.theOldUrlStopsWorking')}</p>
          {dialogError && (
            <p role="alert" className="ui-error">
              {dialogError}
            </p>
          )}
        </Dialog>
      )}
      {confirm === 'disable' && (
        <Dialog
          open
          onOpenChange={(open) => !open && closeConfirm()}
          title={t('WebhookCard.disableWebhook')}
          footer={
            <>
              <Button variant="ghost" onClick={closeConfirm}>
                {t('WebhookCard.cancel')}
              </Button>
              <Button variant="danger" loading={disable.isPending} onClick={onDisable}>
                {t('WebhookCard.disable')}
              </Button>
            </>
          }
        >
          <p className="ui-dialog-text">{t('WebhookCard.theWebhookUrlStopsWorking')}</p>
          {dialogError && (
            <p role="alert" className="ui-error">
              {dialogError}
            </p>
          )}
        </Dialog>
      )}
      {issued && (
        <Dialog
          open
          onOpenChange={(open) => !open && setIssued(null)}
          title={t('WebhookCard.yourWebhookUrl')}
          footer={
            <Button variant="primary" onClick={() => setIssued(null)}>
              {t('WebhookCard.done')}
            </Button>
          }
        >
          <p className="otsecret-notice">{t('WebhookCard.thisUrlIsShownOnly')}</p>
          <OneTimeSecret label={t('WebhookCard.webhookUrl')} value={url} />
          <p className="webhook__text">{t('WebhookCard.triggerItFromACi')}</p>
          <pre className="otsecret-code">{t('WebhookCard.curlXPostUrl', { url })}</pre>
        </Dialog>
      )}
    </Card>
  )
}
