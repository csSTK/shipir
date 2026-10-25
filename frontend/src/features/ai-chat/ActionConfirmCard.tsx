import { Button, Badge, type BadgeTone } from '../../shared/ui/index.ts'
import type { ActionStatus, ToolCall } from './api.ts'
import { toolLabel } from './toolLabel.ts'

interface Props {
  call: ToolCall
  status: ActionStatus
  /** Hides Confirm/Reject (e.g. the ADMIN view of a shared chat). */
  readOnly?: boolean
  busy?: boolean
  error?: string | null
  onConfirm?: () => void
  onReject?: () => void
}

const STATUS_TONE: Record<ActionStatus, BadgeTone> = { PENDING: 'warn', CONFIRMED: 'ok', REJECTED: 'muted' }

const STATUS_LABEL: Record<ActionStatus, string> = {
  PENDING: 'Waiting for confirmation',
  CONFIRMED: 'Confirmed',
  REJECTED: 'Rejected',
}

function argumentEntries(raw: unknown): [string, string][] {
  let value = raw
  if (typeof value === 'string') {
    try {
      value = JSON.parse(value)
    } catch {
      return []
    }
  }
  if (!value || typeof value !== 'object') return []
  return Object.entries(value).map(([k, v]) => [k, typeof v === 'string' ? v : JSON.stringify(v)])
}

/** A write the assistant wants to run: shows what will happen and asks the user to confirm. */
export function ActionConfirmCard({ call, status, readOnly = false, busy = false, error = null, onConfirm, onReject }: Props) {
  const args = argumentEntries(call.arguments)
  return (
    <section className={`chat-card chat-card--${status.toLowerCase()}`} aria-label={`Action: ${toolLabel(call.name)}`}>
      <div className="chat-card__head">
        <span className="chat-card__tool">{toolLabel(call.name)}</span>
        <Badge tone={STATUS_TONE[status]}>{STATUS_LABEL[status]}</Badge>
      </div>
      <p className="chat-card__desc">{call.description ?? `Run ${toolLabel(call.name)}`}</p>
      {call.fromToolContent && (
        <p className="chat-card__notice" role="note">
          This proposal was made after reading data from your environment (names, files, notifications written by
          others). Check every value below before you confirm.
        </p>
      )}
      {args.length > 0 && (
        <dl className="chat-card__args">
          {args.map(([k, v]) => (
            <div key={k}>
              <dt>{k}</dt>
              <dd>{v}</dd>
            </div>
          ))}
        </dl>
      )}
      {error && (
        <p role="alert" className="ui-error">
          {error}
        </p>
      )}
      {status === 'PENDING' && !readOnly && (
        <div className="chat-card__actions">
          <Button onClick={onReject} disabled={busy}>
            Reject
          </Button>
          <Button variant="primary" onClick={onConfirm} disabled={busy} loading={busy}>
            Confirm
          </Button>
        </div>
      )}
    </section>
  )
}
