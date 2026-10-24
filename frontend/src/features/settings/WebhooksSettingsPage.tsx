import { Pencil, Send, Trash2 } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { ApiError } from '../../shared/api/client.ts'
import { Badge, Button, ConfirmDialog, Dialog, ResourceTable, Switch, TextField, type ResourceColumn } from '../../shared/ui/index.ts'
import type { RowActions } from '../../shared/ui/ResourceTable.tsx'
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
import './SettingsPage.css'

const NAME_MAX = 100
const EVENT_TYPES = Object.keys(WEBHOOK_EVENT_LABELS) as WebhookEventType[]

function errorMessage(error: unknown, fallback: string): string {
  if (error instanceof ApiError) {
    if (error.code === 'WEBHOOK_NAME_TAKEN' || error.status === 409) return 'This name is already in use.'
    if (error.code === 'ENCRYPTION_KEY_MISSING')
      return 'The server has no encryption key. Set SHIPIR_ENCRYPTION_KEY to store webhook URLs.'
    if (error.code === 'INVALID_WEBHOOK_URL') return 'The URL must be an absolute http or https address.'
    if (error.code === 'WEBHOOK_NOT_FOUND') return 'This webhook no longer exists.'
    if (error.status === 400 && error.details?.length) return error.details.join(' ')
    if (error.status === 400) return 'Check the entered values. The URL must be an absolute http(s) address.'
  }
  return fallback
}

function WebhookDialog({ webhook, onClose }: { webhook: Webhook | null; onClose: () => void }) {
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
    if (trimmed.length < 1 || trimmed.length > NAME_MAX) return setProblem(`The name must be 1 to ${NAME_MAX} characters.`)
    if (!webhook && url.trim() === '') return setProblem('Enter the webhook URL.')
    if (events.length === 0) return setProblem('Select at least one event.')
    setProblem(null)
    const input = {
      name: trimmed,
      ...(url.trim() !== '' ? { url: url.trim() } : {}),
      eventTypes: events,
      enabled,
    }
    const options = { onSuccess: onClose, onError: (err: unknown) => setProblem(errorMessage(err, 'Could not save the webhook. Try again.')) }
    if (webhook) update.mutate({ id: webhook.id, input }, options)
    else create.mutate(input, options)
  }

  return (
    <Dialog
      open
      onOpenChange={(open) => !open && onClose()}
      title={webhook ? 'Edit webhook' : 'Add webhook'}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button variant="primary" type="submit" form="webhook-form" loading={pending}>
            Save
          </Button>
        </>
      }
    >
      <form id="webhook-form" onSubmit={submit} className="settings__form">
        <TextField label="Name" value={name} onChange={(e) => setName(e.target.value)} autoFocus autoComplete="off" />
        <TextField
          label="Webhook URL"
          type="password"
          value={url}
          onChange={(e) => setUrl(e.target.value)}
          autoComplete="off"
          placeholder={webhook ? 'Leave empty to keep the current URL' : 'https://'}
          help={webhook ? `Current: ${webhook.urlHint}` : undefined}
        />
        <fieldset className="settings__group">
          <legend className="settings__legend">Events</legend>
          {EVENT_TYPES.map((type) => (
            <label key={type} className="settings__check">
              <input type="checkbox" checked={events.includes(type)} onChange={() => toggle(type)} />
              {WEBHOOK_EVENT_LABELS[type]}
            </label>
          ))}
        </fieldset>
        <Switch label="Enabled" checked={enabled} onChange={(e) => setEnabled(e.target.checked)} />
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
  if (status === 'TIMEOUT') return 'the receiver timed out'
  if (status === 'FAILED') return 'the receiver could not be reached'
  if (status.startsWith('HTTP_')) return `the receiver answered HTTP ${status.slice(5)}`
  return status
}

function deliveryText(w: Webhook): string {
  if (!w.lastDeliveryAt || !w.lastDeliveryStatus) return 'No delivery yet'
  const when = new Date(w.lastDeliveryAt).toLocaleString()
  return `${w.lastDeliveryStatus === 'SUCCESS' ? 'Delivered' : `Failed, ${statusText(w.lastDeliveryStatus)}`} · ${when}`
}

/** Settings > Notifications: ADMIN webhook management. The caller restricts access to ADMIN. */
export function WebhooksSettingsPage() {
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
    { id: 'name', header: 'Name', sortValue: (w) => w.name, defaultWidth: 200, hideable: false, render: (w) => <strong>{w.name}</strong> },
    { id: 'url', header: 'URL', defaultWidth: 240, render: (w) => <span className="settings__mono">{w.urlHint}</span> },
    {
      id: 'events',
      header: 'Events',
      defaultWidth: 240,
      render: (w) => w.eventTypes.map((t) => WEBHOOK_EVENT_LABELS[t]).join(', '),
    },
    {
      id: 'status',
      header: 'Status',
      sortValue: (w) => (w.enabled ? 0 : 1),
      defaultWidth: 110,
      render: (w) => <Badge tone={w.enabled ? 'ok' : 'muted'}>{w.enabled ? 'Enabled' : 'Disabled'}</Badge>,
    },
    { id: 'delivery', header: 'Last delivery', defaultWidth: 260, render: (w) => deliveryText(w) },
  ]

  function rowActions(w: Webhook): RowActions {
    return {
      primary: [
        { label: 'Send test to', icon: Send, disabled: test.isPending, onSelect: () => test.mutate(w.id) },
        { label: 'Edit', icon: Pencil, onSelect: () => setDialog({ kind: 'edit', webhook: w }) },
        { label: 'Delete', icon: Trash2, danger: true, onSelect: () => setDialog({ kind: 'delete', webhook: w }) },
      ],
    }
  }

  return (
    <SettingsPage
      wide
      title="Notification webhooks"
      subtitle="Notifications are sent as JSON to each enabled webhook. Saved URLs are never shown again."
      actions={
        <Button variant="primary" onClick={() => setDialog({ kind: 'edit', webhook: null })}>
          Add webhook
        </Button>
      }
    >
      {test.isPending && <p className="settings__muted">Sending test…</p>}
      {!test.isPending && test.data && (
        <p role="status" className={test.data.ok ? 'settings__ok' : 'settings__error'}>
          {test.data.ok ? 'Test delivered.' : `Test failed: ${statusText(test.data.status)}.`}
        </p>
      )}
      {!test.isPending && test.isError && (
        <p role="status" className="settings__error">
          {`Test failed: ${errorMessage(test.error, 'could not reach the server.')}`}
        </p>
      )}
      <ResourceTable
        label="webhooks"
        columns={columns}
        rows={data}
        rowKey={(w) => w.id}
        searchText={(w) => `${w.name} ${w.urlHint}`}
        defaultSort="name"
        emptyMessage="No webhooks yet."
        isPending={isPending}
        error={isError ? 'Could not load webhooks.' : null}
        onRetry={() => void refetch()}
        rowActions={rowActions}
        rowLabel={(w) => w.name}
      />
      {dialog?.kind === 'edit' && <WebhookDialog webhook={dialog.webhook} onClose={close} />}
      {dialog?.kind === 'delete' && (
        <ConfirmDialog
          open
          onOpenChange={(open) => !open && close()}
          title="Delete webhook"
          confirmLabel="Delete"
          danger
          loading={remove.isPending}
          onConfirm={() =>
            remove.mutate(dialog.webhook.id, {
              onSuccess: close,
              onError: (err) => setDeleteProblem(errorMessage(err, 'Could not delete the webhook. Try again.')),
            })
          }
        >
          <p className="settings__text">
            Delete <strong>{dialog.webhook.name}</strong>? It will no longer receive notifications.
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
