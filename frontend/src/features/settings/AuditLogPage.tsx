import { useState, type FormEvent } from 'react'
import { useSearchParams } from 'react-router-dom'
import { AUDIT_ACTION_LABELS, useAuditLog, type AuditLogEntry } from './api.ts'
import './AuditLogPage.css'

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
export function AuditLogPage() {
  const [params, setParams] = useSearchParams()
  const action = params.get('action') ?? ''
  const username = params.get('username') ?? ''
  const from = params.get('from') ?? ''
  const to = params.get('to') ?? ''
  const pageNumber = Math.max(1, Number(params.get('page')) || 1)

  const { data, isPending, isError } = useAuditLog({
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

  return (
    <section className="al">
      <h2 className="al-title">Audit log</h2>
      <form className="al-filters" onSubmit={apply}>
        <label className="al-field">
          <span>Action</span>
          <select value={draft.action} onChange={(e) => setDraft({ ...draft, action: e.target.value })}>
            <option value="">All actions</option>
            {Object.entries(AUDIT_ACTION_LABELS).map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </select>
        </label>
        <label className="al-field">
          <span>User</span>
          <input value={draft.username} onChange={(e) => setDraft({ ...draft, username: e.target.value })} />
        </label>
        <label className="al-field">
          <span>From</span>
          <input type="date" value={draft.from} onChange={(e) => setDraft({ ...draft, from: e.target.value })} />
        </label>
        <label className="al-field">
          <span>To</span>
          <input type="date" value={draft.to} onChange={(e) => setDraft({ ...draft, to: e.target.value })} />
        </label>
        <div className="al-actions">
          <button type="submit" className="al-btn al-btn--primary">
            Apply
          </button>
          <button type="button" className="al-btn" onClick={reset}>
            Reset
          </button>
        </div>
      </form>

      {isPending && <div aria-busy="true" className="al-muted">Loading…</div>}
      {isError && (
        <p role="alert" className="al-error">
          Could not load the audit log.
        </p>
      )}
      {data && data.items.length === 0 && <p className="al-muted">No entries match.</p>}
      {data && data.items.length > 0 && (
        <>
          <div className="al-scroll">
            <table className="al-table">
              <thead>
                <tr>
                  <th>Time</th>
                  <th>User</th>
                  <th>Action</th>
                  <th>Outcome</th>
                  <th>Target</th>
                </tr>
              </thead>
              <tbody>
                {data.items.map((entry) => (
                  <tr key={entry.id}>
                    <td>{formatTime(entry.occurredAt)}</td>
                    <td>{entry.username}</td>
                    <td>{AUDIT_ACTION_LABELS[entry.action] ?? entry.action}</td>
                    <td>
                      <span className={entry.outcome === 'FAILURE' ? 'al-outcome al-outcome--failure' : 'al-outcome'}>
                        {entry.outcome === 'FAILURE' ? 'Failure' : 'Success'}
                      </span>
                    </td>
                    <td>{target(entry)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <nav className="al-pager" aria-label="Pagination">
            <button className="al-btn" disabled={pageNumber <= 1} onClick={() => go(pageNumber - 1)}>
              Previous
            </button>
            <span className="al-muted">
              Page {pageNumber} of {pages}
            </span>
            <button className="al-btn" disabled={pageNumber >= pages} onClick={() => go(pageNumber + 1)}>
              Next
            </button>
          </nav>
        </>
      )}
    </section>
  )
}
