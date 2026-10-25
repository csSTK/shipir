import { useState } from 'react'
import { ApiError } from '../../../shared/api/client.ts'
import { Badge, Button, Card, Dialog, Skeleton } from '../../../shared/ui/index.ts'
import { OneTimeSecret } from '../../one-time-secret/index.ts'
import { useDisableWebhook, useIssueWebhook, useWebhookStatus, type WebhookIssued } from './api.ts'
import './webhook.css'

type Confirm = 'rotate' | 'disable' | null

function formatDate(iso: string | null): string {
  return iso ? new Date(iso).toLocaleString() : ''
}

function describe(error: unknown, fallback: string): string {
  if (error instanceof ApiError) {
    if (error.code === 'WEBHOOK_ALREADY_ENABLED') return 'The webhook is already enabled. Rotate it to get a new URL.'
    if (error.code === 'WEBHOOK_NOT_FOUND') return 'The webhook is not enabled.'
    if (error.status === 403) return 'You are not allowed to change this webhook.'
  }
  return fallback
}

/**
 * Redeploy webhook of a managed deployment. The URL contains the secret, so it only ever exists in `issued`
 * (component state) while the one-time reveal is open: never in a list, the route, storage or a log.
 */
export function WebhookCard({ hostId, deploymentId, canOperate }: { hostId: string; deploymentId: string; canOperate: boolean }) {
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
      onError: (err) => setMessage(describe(err, 'Could not enable the webhook. Please try again.')),
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
      onError: (err) => setDialogError(describe(err, 'Could not rotate the webhook. Please try again.')),
    })
  }

  function onDisable() {
    setDialogError(null)
    disable.mutate(undefined, {
      onSuccess: closeConfirm,
      onError: (err) => setDialogError(describe(err, 'Could not disable the webhook. Please try again.')),
    })
  }

  const enabled = status.data?.enabled === true

  return (
    <Card className="webhook" role="region" aria-labelledby={`webhook-${deploymentId}`}>
      <div className="webhook__head">
        <h3 className="webhook__title" id={`webhook-${deploymentId}`}>
          Redeploy webhook
        </h3>
        {status.data && <Badge tone={enabled ? 'ok' : 'muted'}>{enabled ? 'Enabled' : 'Disabled'}</Badge>}
      </div>
      <p className="webhook__text">
        A POST to the webhook URL updates this deployment without signing in: it pulls newer images and recreates the
        containers (Git deployments pull the repository and redeploy). It runs as the system and answers 409 while a
        deployment is running. The URL is a secret, so Shipir shows it only once.
      </p>

      {status.isPending && <Skeleton height="var(--space-6)" />}
      {status.isError && (
        <p role="alert" className="ui-error">
          Could not load the webhook status.
        </p>
      )}
      {status.data && enabled && (
        <p className="webhook__meta">
          Enabled {formatDate(status.data.createdAt)}
          {status.data.rotatedAt ? ` · Rotated ${formatDate(status.data.rotatedAt)}` : ''}
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
              <Button onClick={() => setConfirm('rotate')}>Rotate</Button>
              <Button variant="danger" onClick={() => setConfirm('disable')}>
                Disable
              </Button>
            </>
          ) : (
            <Button variant="primary" onClick={onEnable} loading={enable.isPending}>
              Enable webhook
            </Button>
          )}
        </div>
      )}

      {confirm === 'rotate' && (
        <Dialog
          open
          onOpenChange={(open) => !open && closeConfirm()}
          title="Rotate webhook"
          footer={
            <>
              <Button variant="ghost" onClick={closeConfirm}>
                Cancel
              </Button>
              <Button variant="primary" loading={rotate.isPending} onClick={onRotate}>
                Rotate
              </Button>
            </>
          }
        >
          <p className="ui-dialog-text">The old URL stops working immediately. You get a new URL, shown once.</p>
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
          title="Disable webhook"
          footer={
            <>
              <Button variant="ghost" onClick={closeConfirm}>
                Cancel
              </Button>
              <Button variant="danger" loading={disable.isPending} onClick={onDisable}>
                Disable
              </Button>
            </>
          }
        >
          <p className="ui-dialog-text">The webhook URL stops working. You can enable it again later with a new URL.</p>
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
          title="Your webhook URL"
          footer={
            <Button variant="primary" onClick={() => setIssued(null)}>
              Done
            </Button>
          }
        >
          <p className="otsecret-notice">This URL is shown only once. Store it now, anyone with it can trigger a redeploy.</p>
          <OneTimeSecret label="Webhook URL" value={url} />
          <p className="webhook__text">Trigger it from a CI job or a registry hook:</p>
          <pre className="otsecret-code">{`curl -X POST ${url}`}</pre>
        </Dialog>
      )}
    </Card>
  )
}
