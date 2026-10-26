import { useState, type FormEvent } from 'react'
import { Button, Card, Skeleton, TextField } from '../../shared/ui/index.ts'
import { ApiError } from '../../shared/api/client.ts'
import { useRetentionSettings, useSaveRetentionSettings, type RetentionSettings } from './api.ts'
import { SettingsPage } from './SettingsPage.tsx'
import './SettingsPage.css'

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
  const [stats, setStats] = useState(String(initial.statsDays))
  const [problem, setProblem] = useState<string | null>(null)
  const [saved, setSaved] = useState(false)
  const save = useSaveRetentionSettings()

  function submit(e: FormEvent) {
    e.preventDefault()
    const auditLogDays = parseDays(audit)
    const notificationDays = parseDays(notification)
    const chatSessionDays = parseDays(chat)
    const statsDays = parseDays(stats)
    if (auditLogDays === null || notificationDays === null || chatSessionDays === null || statsDays === null) {
      setSaved(false)
      return setProblem(RANGE_MESSAGE)
    }
    setProblem(null)
    setSaved(false)
    save.mutate(
      { auditLogDays, notificationDays, chatSessionDays, statsDays },
      {
        onSuccess: () => setSaved(true),
        onError: (err) => setProblem(errorMessage(err)),
      },
    )
  }

  const field = (label: string, value: string, set: (v: string) => void) => (
    <TextField
      label={label}
      type="number"
      inputMode="numeric"
      min={MIN_DAYS}
      max={MAX_DAYS}
      step={1}
      value={value}
      onChange={(e) => {
        set(e.target.value)
        setSaved(false)
      }}
    />
  )

  return (
    <Card>
      <form className="settings__form" onSubmit={submit} noValidate>
        {field('Audit log retention (days)', audit, setAudit)}
        {field('Notification retention (days)', notification, setNotification)}
        {field('Chat session retention (days)', chat, setChat)}
        {field('Stats history (days)', stats, setStats)}
        <p className="settings__muted">Older entries are removed once a day. Values from 1 to 3650 days.</p>
        {problem && (
          <p role="alert" className="settings__error">
            {problem}
          </p>
        )}
        {saved && (
          <p role="status" className="settings__ok">
            Retention settings saved.
          </p>
        )}
        <div className="settings__actions">
          <Button variant="primary" type="submit" loading={save.isPending}>
            Save
          </Button>
        </div>
      </form>
    </Card>
  )
}

/** Settings > Retention: ADMIN sets how long audit log, notification, chat session and stats history entries are kept. */
export function RetentionSettingsPage() {
  const { data, isPending, isError } = useRetentionSettings()

  return (
    <SettingsPage title="Retention" subtitle="How long audit log, notification, chat session and stats history entries are kept.">
      {isPending && <Skeleton width="100%" height="var(--space-8)" />}
      {isError && (
        <p role="alert" className="settings__error">
          Could not load the retention settings.
        </p>
      )}
      {data && <RetentionForm initial={data} />}
    </SettingsPage>
  )
}
