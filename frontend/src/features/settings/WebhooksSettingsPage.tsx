import { useEffect, useId, useRef, useState, type FormEvent, type ReactNode } from 'react'
import { ApiError } from '../../shared/api/client.ts'
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
import './WebhooksSettingsPage.css'

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

function Modal({ title, titleId, onClose, children }: { title: string; titleId: string; onClose: () => void; children: ReactNode }) {
  const ref = useRef<HTMLDivElement>(null)
  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null
    ref.current?.focus()
    return () => previous?.focus()
  }, [])
  return (
    <div className="wh-scrim" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div
        ref={ref}
        className="wh-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        tabIndex={-1}
        onKeyDown={(e) => e.key === 'Escape' && onClose()}
      >
        <h2 id={titleId} className="wh-dialog__title">
          {title}
        </h2>
        {children}
      </div>
    </div>
  )
}

function WebhookDialog({ webhook, onClose }: { webhook: Webhook | null; onClose: () => void }) {
  const titleId = useId()
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
    <Modal title={webhook ? 'Edit webhook' : 'Add webhook'} titleId={titleId} onClose={onClose}>
      <form onSubmit={submit} className="wh-form">
        <label className="wh-field">
          <span>Name</span>
          <input value={name} onChange={(e) => setName(e.target.value)} autoFocus autoComplete="off" />
        </label>
        <label className="wh-field">
          <span>Webhook URL</span>
          <input
            type="password"
            value={url}
            onChange={(e) => setUrl(e.target.value)}
            autoComplete="off"
            placeholder={webhook ? 'Leave empty to keep the current URL' : 'https://'}
          />
        </label>
        {webhook && <small className="wh-muted">Current: {webhook.urlHint}</small>}
        <fieldset className="wh-field wh-events">
          <legend>Events</legend>
          {EVENT_TYPES.map((type) => (
            <label key={type} className="wh-check">
              <input type="checkbox" checked={events.includes(type)} onChange={() => toggle(type)} />
              {WEBHOOK_EVENT_LABELS[type]}
            </label>
          ))}
        </fieldset>
        <label className="wh-check">
          <input type="checkbox" role="switch" checked={enabled} onChange={(e) => setEnabled(e.target.checked)} />
          Enabled
        </label>
        {problem && (
          <p role="alert" className="wh-error">
            {problem}
          </p>
        )}
        <div className="wh-actions">
          <button type="button" className="wh-btn" onClick={onClose}>
            Cancel
          </button>
          <button type="submit" className="wh-btn wh-btn--primary" disabled={pending}>
            Save
          </button>
        </div>
      </form>
    </Modal>
  )
}

function DeleteDialog({ webhook, onClose }: { webhook: Webhook; onClose: () => void }) {
  const titleId = useId()
  const [problem, setProblem] = useState<string | null>(null)
  const remove = useDeleteWebhook()
  return (
    <Modal title="Delete webhook" titleId={titleId} onClose={onClose}>
      <p className="wh-text">
        Delete <strong>{webhook.name}</strong>? It will no longer receive notifications.
      </p>
      {problem && (
        <p role="alert" className="wh-error">
          {problem}
        </p>
      )}
      <div className="wh-actions">
        <button type="button" className="wh-btn" onClick={onClose}>
          Cancel
        </button>
        <button
          type="button"
          className="wh-btn wh-btn--danger"
          disabled={remove.isPending}
          onClick={() =>
            remove.mutate(webhook.id, {
              onSuccess: onClose,
              onError: (err) => setProblem(errorMessage(err, 'Could not delete the webhook. Try again.')),
            })
          }
        >
          Delete
        </button>
      </div>
    </Modal>
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

function WebhookRow({ webhook, onEdit, onDelete }: { webhook: Webhook; onEdit: () => void; onDelete: () => void }) {
  const test = useTestWebhook()
  const result = test.data
  return (
    <li className="wh-row">
      <div className="wh-row__main">
        <strong>{webhook.name}</strong>
        <span className="wh-muted">{webhook.urlHint}</span>
        <span className="wh-muted">
          {webhook.eventTypes.map((t) => WEBHOOK_EVENT_LABELS[t]).join(', ')} · {webhook.enabled ? 'Enabled' : 'Disabled'}
        </span>
        <span className="wh-muted">{deliveryText(webhook)}</span>
        {test.isPending && <span className="wh-muted">Sending test…</span>}
        {!test.isPending && result && (
          <span role="status" className={result.ok ? 'wh-ok' : 'wh-error'}>
            {result.ok ? 'Test delivered.' : `Test failed: ${statusText(result.status)}.`}
          </span>
        )}
        {!test.isPending && test.isError && (
          <span role="status" className="wh-error">
            {`Test failed: ${errorMessage(test.error, 'could not reach the server.')}`}
          </span>
        )}
      </div>
      <div className="wh-row__actions">
        <button type="button" className="wh-btn" aria-label={`Send test to ${webhook.name}`} disabled={test.isPending} onClick={() => test.mutate(webhook.id)}>
          Send test
        </button>
        <button type="button" className="wh-btn" aria-label={`Edit ${webhook.name}`} onClick={onEdit}>
          Edit
        </button>
        <button type="button" className="wh-btn wh-btn--danger-text" aria-label={`Delete ${webhook.name}`} onClick={onDelete}>
          Delete
        </button>
      </div>
    </li>
  )
}

/** Settings > Notifications: ADMIN webhook management. The caller restricts access to ADMIN. */
export function WebhooksSettingsPage() {
  const { data, isPending, isError } = useWebhooks()
  const [dialog, setDialog] = useState<{ kind: 'edit'; webhook: Webhook | null } | { kind: 'delete'; webhook: Webhook } | null>(null)
  const close = () => setDialog(null)

  return (
    <section className="wh">
      <div className="wh-bar">
        <h2 className="wh-title">Notification webhooks</h2>
        <button type="button" className="wh-btn wh-btn--primary" onClick={() => setDialog({ kind: 'edit', webhook: null })}>
          Add webhook
        </button>
      </div>
      <p className="wh-muted">Host status changes are sent as JSON to each enabled webhook. Saved URLs are never shown again.</p>
      {isPending && <div aria-busy="true" className="wh-muted">Loading…</div>}
      {isError && <p role="alert" className="wh-error">Could not load webhooks.</p>}
      {data && data.length === 0 && <p className="wh-muted">No webhooks yet.</p>}
      {data && data.length > 0 && (
        <ul className="wh-list">
          {data.map((w) => (
            <WebhookRow
              key={w.id}
              webhook={w}
              onEdit={() => setDialog({ kind: 'edit', webhook: w })}
              onDelete={() => setDialog({ kind: 'delete', webhook: w })}
            />
          ))}
        </ul>
      )}
      {dialog?.kind === 'edit' && <WebhookDialog webhook={dialog.webhook} onClose={close} />}
      {dialog?.kind === 'delete' && <DeleteDialog webhook={dialog.webhook} onClose={close} />}
    </section>
  )
}
