import { useState, type FormEvent } from 'react'
import { ApiError } from '../../shared/api/client.ts'
import { useRetentionSettings, useSaveRetentionSettings, type RetentionSettings } from './api.ts'
import './RetentionSettingsPage.css'

const MIN_DAYS = 1
const MAX_DAYS = 3650
const RANGE_MESSAGE = `Enter a value from ${MIN_DAYS} to ${MAX_DAYS} days.`

/** Whole number inside the range; mirrors the backend bounds. */
function parseDays(raw: string): number | null {
  if (!/^\d+$/.test(raw.trim())) return null
  const value = Number(raw)
  return value >= MIN_DAYS && value <= MAX_DAYS ? value : null
}

function errorMessage(error: unknown): string {
  if (error instanceof ApiError && error.details?.length) return error.details.join(' ')
  return 'Could not save the retention settings. Try again.'
}

function RetentionForm({ initial }: { initial: RetentionSettings }) {
  const [audit, setAudit] = useState(String(initial.auditLogDays))
  const [notification, setNotification] = useState(String(initial.notificationDays))
  const [chat, setChat] = useState(String(initial.chatSessionDays))
  const [problem, setProblem] = useState<string | null>(null)
  const [saved, setSaved] = useState(false)
  const save = useSaveRetentionSettings()

  function submit(e: FormEvent) {
    e.preventDefault()
    const auditLogDays = parseDays(audit)
    const notificationDays = parseDays(notification)
    const chatSessionDays = parseDays(chat)
    if (auditLogDays === null || notificationDays === null || chatSessionDays === null) {
      setSaved(false)
      return setProblem(RANGE_MESSAGE)
    }
    setProblem(null)
    setSaved(false)
    save.mutate(
      { auditLogDays, notificationDays, chatSessionDays },
      {
        onSuccess: () => setSaved(true),
        onError: (err) => setProblem(errorMessage(err)),
      },
    )
  }

  return (
    <form className="rt-form" onSubmit={submit} noValidate>
      <label className="rt-field">
        <span>Audit log retention (days)</span>
        <input
          type="number"
          inputMode="numeric"
          min={MIN_DAYS}
          max={MAX_DAYS}
          step={1}
          value={audit}
          onChange={(e) => {
            setAudit(e.target.value)
            setSaved(false)
          }}
        />
      </label>
      <label className="rt-field">
        <span>Notification retention (days)</span>
        <input
          type="number"
          inputMode="numeric"
          min={MIN_DAYS}
          max={MAX_DAYS}
          step={1}
          value={notification}
          onChange={(e) => {
            setNotification(e.target.value)
            setSaved(false)
          }}
        />
      </label>
      <label className="rt-field">
        <span>Chat session retention (days)</span>
        <input
          type="number"
          inputMode="numeric"
          min={MIN_DAYS}
          max={MAX_DAYS}
          step={1}
          value={chat}
          onChange={(e) => {
            setChat(e.target.value)
            setSaved(false)
          }}
        />
      </label>
      <p className="rt-muted">Older entries are removed once a day. Values from 1 to 3650 days.</p>
      {problem && (
        <p role="alert" className="rt-error">
          {problem}
        </p>
      )}
      {saved && (
        <p role="status" className="rt-ok">
          Retention settings saved.
        </p>
      )}
      <div className="rt-actions">
        <button type="submit" className="rt-btn rt-btn--primary" disabled={save.isPending}>
          {save.isPending ? 'Saving…' : 'Save'}
        </button>
      </div>
    </form>
  )
}

/** Settings > Retention: ADMIN sets how long audit log, notification and chat session entries are kept. */
export function RetentionSettingsPage() {
  const { data, isPending, isError } = useRetentionSettings()

  return (
    <section className="rt">
      <h2 className="rt-title">Retention</h2>
      {isPending && <div aria-busy="true" className="rt-muted">Loading…</div>}
      {isError && (
        <p role="alert" className="rt-error">
          Could not load the retention settings.
        </p>
      )}
      {data && <RetentionForm initial={data} />}
    </section>
  )
}
