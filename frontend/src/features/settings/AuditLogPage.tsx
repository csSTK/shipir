import { useState, type FormEvent } from 'react'
import { useSearchParams } from 'react-router-dom'
import { Badge, Button, Card, ResourceTable, Select, TextField, type ResourceColumn } from '../../shared/ui/index.ts'
import { AUDIT_ACTION_LABELS, useAuditLog, type AuditLogEntry } from './api.ts'
import { Pager } from './Pager.tsx'
import { SettingsPage } from './SettingsPage.tsx'

const PAGE_SIZE = 20

/** Local start of the picked day. */
function startOfDay(date: string): string | undefined {
  const [y, m, d] = date.split('-').map(Number)
  return y ? new Date(y, m - 1, d, 0, 0, 0, 0).toISOString() : undefined
}

/** Local last millisecond of the picked day (the backend bound is inclusive). */
function endOfDay(date: string): string | undefined {
  const [y, m, d] = date.split('-').map(Number)
  return y ? new Date(y, m - 1, d, 23, 59, 59, 999).toISOString() : undefined
}

function target(entry: AuditLogEntry): string {
  const text = [entry.targetType, entry.targetId].filter(Boolean).join(' ')
  return text || '—'
}

const formatTime = (iso: string) =>
  new Date(iso).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'medium' })

/** Settings > Audit log: ADMIN browses the audit trail; filters live in the URL. */
/** A password-only LOGIN that still awaits the second factor reads as such. */
function actionLabel(e: AuditLogEntry): string {
  if (e.action === 'LOGIN' && e.details?.mfaPending === true) return 'Login (awaiting 2FA)'
  return AUDIT_ACTION_LABELS[e.action] ?? e.action
}

export function AuditLogPage() {
  const [params, setParams] = useSearchParams()
  const action = params.get('action') ?? ''
  const username = params.get('username') ?? ''
  const from = params.get('from') ?? ''
  const to = params.get('to') ?? ''
  const pageNumber = Math.max(1, Number(params.get('page')) || 1)

  const { data, isPending, isError, refetch } = useAuditLog({
    page: pageNumber - 1,
    size: PAGE_SIZE,
    action: action || undefined,
    username: username || undefined,
    from: from ? startOfDay(from) : undefined,
    to: to ? endOfDay(to) : undefined,
  })

  const [draft, setDraft] = useState({ action, username, from, to })

  function update(next: Record<string, string>) {
    const out = new URLSearchParams()
    for (const [k, v] of Object.entries(next)) if (v) out.set(k, v)
    setParams(out)
  }

  function apply(e: FormEvent) {
    e.preventDefault()
    update({ ...draft, username: draft.username.trim() })
  }

  function reset() {
    setDraft({ action: '', username: '', from: '', to: '' })
    update({})
  }

  const go = (p: number) => update({ action, username, from, to, page: p > 1 ? String(p) : '' })
  const pages = data ? Math.max(1, Math.ceil(data.totalElements / data.size)) : 1

  const columns: ResourceColumn<AuditLogEntry>[] = [
    { id: 'time', header: 'Time', defaultWidth: 200, hideable: false, render: (e) => formatTime(e.occurredAt) },
    { id: 'user', header: 'User', defaultWidth: 140, render: (e) => e.username },
    { id: 'action', header: 'Action', defaultWidth: 220, render: (e) => actionLabel(e) },
    {
      id: 'outcome',
      header: 'Outcome',
      defaultWidth: 110,
      render: (e) => <Badge tone={e.outcome === 'FAILURE' ? 'bad' : 'ok'}>{e.outcome === 'FAILURE' ? 'Failure' : 'Success'}</Badge>,
    },
    { id: 'target', header: 'Target', defaultWidth: 280, render: (e) => <span className="settings__mono">{target(e)}</span> },
  ]

  return (
    <SettingsPage wide title="Audit log" subtitle="Who did what, newest first.">
      <Card>
        <form className="settings__filters" onSubmit={apply}>
          <Select label="Action" value={draft.action} onChange={(e) => setDraft({ ...draft, action: e.target.value })}>
            <option value="">All actions</option>
            {Object.entries(AUDIT_ACTION_LABELS).map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </Select>
          <TextField label="User" value={draft.username} onChange={(e) => setDraft({ ...draft, username: e.target.value })} />
          <TextField label="From" type="date" value={draft.from} onChange={(e) => setDraft({ ...draft, from: e.target.value })} />
          <TextField label="To" type="date" value={draft.to} onChange={(e) => setDraft({ ...draft, to: e.target.value })} />
          <div className="settings__filters-actions">
            <Button variant="primary" type="submit">
              Apply
            </Button>
            <Button onClick={reset}>Reset</Button>
          </div>
        </form>
      </Card>

      <ResourceTable
        label="audit log"
        columns={columns}
        rows={data?.items}
        rowKey={(e) => e.id}
        searchText={(e) => `${e.username} ${e.action} ${target(e)}`}
        defaultSort="time"
        emptyMessage="No entries match."
        isPending={isPending}
        error={isError ? 'Could not load the audit log.' : null}
        onRetry={() => void refetch()}
      />
      {data && data.items.length > 0 && <Pager page={pageNumber} pages={pages} onPage={go} />}
    </SettingsPage>
  )
}
