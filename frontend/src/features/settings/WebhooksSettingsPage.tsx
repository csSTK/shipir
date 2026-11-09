import { Pencil, Send, Trash2 } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { ApiError } from '../../shared/api/client.ts'
import { Checkbox, Badge, Button, ConfirmDialog, Dialog, ResourceTable, Switch, TextField, type ResourceColumn, type RowActions } from '../../shared/ui/index.ts'
import {
  WEBHOOK_EVENT_LABELS,
  useCreateWebhook,
  useDeleteWebhook,
  useTestWebhook,
  useUpdateWebhook,
  useWebhooks,
  type Webhook,
  type WebhookEventType,
} from './api.ts'
import { SettingsPage } from './SettingsPage.tsx'
import { useTranslation } from 'react-i18next'
import { Trans } from '../../shared/i18n/Trans.tsx'
import i18n from 'i18next'
import { formatDateTime } from '../../shared/i18n/format.ts'
import './SettingsPage.css'

const NAME_MAX = 100
const EVENT_TYPES = Object.keys(WEBHOOK_EVENT_LABELS) as WebhookEventType[]

function errorMessage(error: unknown, fallback: string): string {
  if (error instanceof ApiError) {
    if (error.code === 'WEBHOOK_NAME_TAKEN' || error.status === 409) return i18n.t('settings:WebhooksSettingsPage.thisNameIsAlreadyIn')
    if (error.code === 'ENCRYPTION_KEY_MISSING')
      return i18n.t('settings:WebhooksSettingsPage.theServerHasNoEncryption')
    if (error.code === 'INVALID_WEBHOOK_URL') return i18n.t('settings:WebhooksSettingsPage.theUrlMustBeAn')
    if (error.code === 'WEBHOOK_NOT_FOUND') return i18n.t('settings:WebhooksSettingsPage.thisWebhookNoLongerExists')
    if (error.status === 400 && error.details?.length) return error.details.join(' ')
    if (error.status === 400) return i18n.t('settings:WebhooksSettingsPage.checkTheEnteredValuesThe')
  }
  return fallback
}

function WebhookDialog({ webhook, onClose }: { webhook: Webhook | null; onClose: () => void }) {
  const { t: tr } = useTranslation('settings')
  const [name, setName] = useState(webhook?.name ?? '')
  const [url, setUrl] = useState('')
  const [events, setEvents] = useState<WebhookEventType[]>(webhook?.eventTypes ?? [])
  const [enabled, setEnabled] = useState(webhook?.enabled ?? true)
  const [problem, setProblem] = useState<string | null>(null)
  const create = useCreateWebhook()
  const update = useUpdateWebhook()
  const pending = create.isPending || update.isPending

  function toggle(type: WebhookEventType) {
    setEvents((current) => (current.includes(type) ? current.filter((t) => t !== type) : [...current, type]))
  }

  function submit(e: FormEvent) {
    e.preventDefault()
    const trimmed = name.trim()
    if (trimmed.length < 1 || trimmed.length > NAME_MAX) return setProblem(tr('WebhooksSettingsPage.theNameMustBe1', { NAME_MAX }))
    if (!webhook && url.trim() === '') return setProblem(tr('WebhooksSettingsPage.enterTheWebhookUrl'))
    if (events.length === 0) return setProblem(tr('WebhooksSettingsPage.selectAtLeastOneEvent'))
    setProblem(null)
    const input = {
      name: trimmed,
      ...(url.trim() !== '' ? { url: url.trim() } : {}),
      eventTypes: events,
      enabled,
    }
    const options = { onSuccess: onClose, onError: (err: unknown) => setProblem(errorMessage(err, tr('WebhooksSettingsPage.couldNotSaveTheWebhook'))) }
    if (webhook) update.mutate({ id: webhook.id, input }, options)
    else create.mutate(input, options)
  }

  return (
    <Dialog
      open
      onOpenChange={(open) => !open && onClose()}
      title={webhook ? tr('WebhooksSettingsPage.editWebhook') : tr('WebhooksSettingsPage.addWebhook')}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            {tr('WebhooksSettingsPage.cancel')}
          </Button>
          <Button variant="primary" type="submit" form="webhook-form" loading={pending}>
            {tr('WebhooksSettingsPage.save')}
          </Button>
        </>
      }
    >
      <form id="webhook-form" onSubmit={submit} className="settings__form">
        <TextField label={tr('WebhooksSettingsPage.name')} value={name} onChange={(e) => setName(e.target.value)} autoFocus autoComplete="off" />
        <TextField
          label={tr('WebhooksSettingsPage.webhookUrl')}
          type="password"
          value={url}
          onChange={(e) => setUrl(e.target.value)}
          autoComplete="off"
          placeholder={webhook ? tr('WebhooksSettingsPage.leaveEmptyToKeepThe') : 'https://'}
          help={webhook ? tr('WebhooksSettingsPage.currentUrlhint', { urlHint: webhook.urlHint }) : undefined}
        />
        <fieldset className="settings__group">
          <legend className="settings__legend">{tr('WebhooksSettingsPage.events')}</legend>
          {EVENT_TYPES.map((type) => (
            <Checkbox key={type} label={WEBHOOK_EVENT_LABELS[type]} checked={events.includes(type)} onChange={() => toggle(type)} />
          ))}
        </fieldset>
        <Switch label={tr('WebhooksSettingsPage.enabled')} checked={enabled} onChange={(e) => setEnabled(e.target.checked)} />
        {problem && (
          <p role="alert" className="settings__error">
            {problem}
          </p>
        )}
      </form>
    </Dialog>
  )
}

function statusText(status: string): string {
  if (status === 'TIMEOUT') return i18n.t('settings:WebhooksSettingsPage.theReceiverTimedOut')
  if (status === 'FAILED') return i18n.t('settings:WebhooksSettingsPage.theReceiverCouldNotBe')
  if (status.startsWith('HTTP_')) return i18n.t('settings:WebhooksSettingsPage.theReceiverAnsweredHttpStatus', { status: status.slice(5) })
  return status
}

function deliveryText(w: Webhook): string {
  if (!w.lastDeliveryAt || !w.lastDeliveryStatus) return i18n.t('settings:WebhooksSettingsPage.noDeliveryYet')
  const when = formatDateTime(w.lastDeliveryAt)
  return `${w.lastDeliveryStatus === 'SUCCESS' ? i18n.t('settings:WebhooksSettingsPage.delivered') : i18n.t('settings:WebhooksSettingsPage.failedStatustext', { statusText: statusText(w.lastDeliveryStatus) })} · ${when}`
}

/** Settings > Notifications: ADMIN webhook management. The caller restricts access to ADMIN. */
export function WebhooksSettingsPage() {
  const { t: tr } = useTranslation('settings')
  const { data, isPending, isError, refetch } = useWebhooks()
  const [dialog, setDialog] = useState<{ kind: 'edit'; webhook: Webhook | null } | { kind: 'delete'; webhook: Webhook } | null>(null)
  const [deleteProblem, setDeleteProblem] = useState<string | null>(null)
  const remove = useDeleteWebhook()
  const test = useTestWebhook()
  const close = () => {
    setDialog(null)
    setDeleteProblem(null)
  }

  const columns: ResourceColumn<Webhook>[] = [
    { id: 'name', header: tr('WebhooksSettingsPage.name'), sortValue: (w) => w.name, defaultWidth: 200, flex: true, hideable: false, render: (w) => <strong>{w.name}</strong> },
    { id: 'url', header: 'URL', defaultWidth: 240, render: (w) => <span className="settings__mono">{w.urlHint}</span> },
    {
      id: 'events',
      header: tr('WebhooksSettingsPage.events'),
      defaultWidth: 240,
      render: (w) => w.eventTypes.map((t) => WEBHOOK_EVENT_LABELS[t]).join(', '),
    },
    {
      id: 'status',
      header: tr('WebhooksSettingsPage.status'),
      sortValue: (w) => (w.enabled ? 0 : 1),
      // Fits the longest badge label ("Deaktiviert" in German) with cell padding; narrower would clip the pill.
      defaultWidth: 140,
      minWidth: 140,
      render: (w) => <Badge tone={w.enabled ? 'ok' : 'muted'}>{w.enabled ? tr('WebhooksSettingsPage.enabled') : tr('WebhooksSettingsPage.disabled')}</Badge>,
    },
    { id: 'delivery', header: tr('WebhooksSettingsPage.lastDelivery'), defaultWidth: 260, flex: true, render: (w) => deliveryText(w) },
  ]

  function rowActions(w: Webhook): RowActions {
    return {
      primary: [
        { label: tr('WebhooksSettingsPage.sendTestTo'), icon: Send, disabled: test.isPending, onSelect: () => test.mutate(w.id) },
        { label: tr('WebhooksSettingsPage.edit'), icon: Pencil, onSelect: () => setDialog({ kind: 'edit', webhook: w }) },
        { label: tr('WebhooksSettingsPage.delete'), icon: Trash2, danger: true, onSelect: () => setDialog({ kind: 'delete', webhook: w }) },
      ],
    }
  }

  return (
    <SettingsPage
      wide
      title={tr('WebhooksSettingsPage.notificationWebhooks')}
      subtitle={tr('WebhooksSettingsPage.notificationsAreSentAsJson')}
      actions={
        <Button variant="primary" onClick={() => setDialog({ kind: 'edit', webhook: null })}>
          {tr('WebhooksSettingsPage.addWebhook')}
        </Button>
      }
    >
      {test.isPending && <p className="settings__muted">{tr('WebhooksSettingsPage.sendingTest')}</p>}
      {!test.isPending && test.data && (
        <p role="status" className={test.data.ok ? 'settings__ok' : 'settings__error'}>
          {test.data.ok ? tr('WebhooksSettingsPage.testDelivered') : tr('WebhooksSettingsPage.testFailedStatustext', { statusText: statusText(test.data.status) })}
        </p>
      )}
      {!test.isPending && test.isError && (
        <p role="status" className="settings__error">
          {tr('WebhooksSettingsPage.testFailedErrormessage', { errorMessage: errorMessage(test.error, i18n.t('settings:WebhooksSettingsPage.couldNotReachTheServer')) })}
        </p>
      )}
      <ResourceTable
        label={tr('WebhooksSettingsPage.webhooks')}
        columns={columns}
        rows={data}
        rowKey={(w) => w.id}
        searchText={(w) => `${w.name} ${w.urlHint}`}
        defaultSort="name"
        emptyMessage={tr('WebhooksSettingsPage.noWebhooksYet')}
        isPending={isPending}
        error={isError ? tr('WebhooksSettingsPage.couldNotLoadWebhooks') : null}
        onRetry={() => void refetch()}
        rowActions={rowActions}
        rowLabel={(w) => w.name}
      />
      {dialog?.kind === 'edit' && <WebhookDialog webhook={dialog.webhook} onClose={close} />}
      {dialog?.kind === 'delete' && (
        <ConfirmDialog
          open
          onOpenChange={(open) => !open && close()}
          title={tr('WebhooksSettingsPage.deleteWebhook')}
          confirmLabel={tr('WebhooksSettingsPage.delete')}
          danger
          loading={remove.isPending}
          onConfirm={() =>
            remove.mutate(dialog.webhook.id, {
              onSuccess: close,
              onError: (err) => setDeleteProblem(errorMessage(err, tr('WebhooksSettingsPage.couldNotDeleteTheWebhook'))),
            })
          }
        >
          <p className="settings__text">
            <Trans t={tr} i18nKey="WebhooksSettingsPage.deleteNameItWillNo" values={{ name: dialog.webhook.name }} components={{ strong: <strong /> }} />
          </p>
          {deleteProblem && (
            <p role="alert" className="settings__error">
              {deleteProblem}
            </p>
          )}
        </ConfirmDialog>
      )}
    </SettingsPage>
  )
}
