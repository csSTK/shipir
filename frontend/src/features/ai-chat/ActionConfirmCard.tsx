import { Button, Badge, type BadgeTone } from '../../shared/ui/index.ts'
import type { ActionStatus, ToolCall } from './api.ts'
import { toolLabel } from './toolLabel.ts'
import { useTranslation } from 'react-i18next'
import i18n from 'i18next'

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
  get PENDING() { return i18n.t('ai-chat:ActionConfirmCard.waitingForConfirmation') },
  get CONFIRMED() { return i18n.t('ai-chat:ActionConfirmCard.confirmed') },
  get REJECTED() { return i18n.t('ai-chat:ActionConfirmCard.rejected') },
}

/** Parsed key/value pairs, or the raw text when the arguments are a string that is not a JSON object. */
function argumentEntries(raw: unknown): { entries: [string, string][]; rawText: string | null } {
  let value = raw
  if (typeof raw === 'string') {
    try {
      value = JSON.parse(raw)
    } catch {
      return { entries: [], rawText: raw.trim() === '' ? null : raw }
    }
  }
  if (!value || typeof value !== 'object') {
    return { entries: [], rawText: typeof raw === 'string' && raw.trim() !== '' ? raw : null }
  }
  const entries = Object.entries(value).map(([k, v]): [string, string] => [k, typeof v === 'string' ? v : JSON.stringify(v)])
  return { entries, rawText: null }
}

/** A write the assistant wants to run: shows what will happen and asks the user to confirm. */
export function ActionConfirmCard({ call, status, readOnly = false, busy = false, error = null, onConfirm, onReject }: Props) {
  const { t } = useTranslation('ai-chat')
  const { entries: args, rawText } = argumentEntries(call.arguments)
  return (
    <section className={`chat-card chat-card--${status.toLowerCase()}`} aria-label={t('ActionConfirmCard.actionToollabel', { toolLabel: toolLabel(call.name) })}>
      <div className="chat-card__head">
        <span className="chat-card__tool">{toolLabel(call.name)}</span>
        <Badge tone={STATUS_TONE[status]}>{STATUS_LABEL[status]}</Badge>
      </div>
      <p className="chat-card__desc">{call.description ?? t('ActionConfirmCard.runToollabel', { toolLabel: toolLabel(call.name) })}</p>
      {call.fromToolContent && (
        <p className="chat-card__notice" role="note">
          {t('ActionConfirmCard.thisProposalWasMadeAfter')}
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
      {rawText !== null && <pre className="chat-card__raw">{rawText}</pre>}
      {error && (
        <p role="alert" className="ui-error">
          {error}
        </p>
      )}
      {status === 'PENDING' && !readOnly && (
        <div className="chat-card__actions">
          <Button onClick={onReject} disabled={busy}>
            {t('ActionConfirmCard.reject')}
          </Button>
          <Button variant="primary" onClick={onConfirm} disabled={busy} loading={busy}>
            {t('ActionConfirmCard.confirm')}
          </Button>
        </div>
      )}
    </section>
  )
}
