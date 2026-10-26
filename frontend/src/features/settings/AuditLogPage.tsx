import { useState, type FormEvent } from 'react'
import { useSearchParams } from 'react-router-dom'
import { Badge, Button, Card, ResourceTable, Select, TextField, type ResourceColumn } from '../../shared/ui/index.ts'
import { AUDIT_ACTION_LABELS, useAuditLog, type AuditLogEntry } from './api.ts'
import { Pager } from './Pager.tsx'
import { SettingsPage } from './SettingsPage.tsx'
import { useTranslation } from 'react-i18next'
import i18n from 'i18next'
import { formatDateTime } from '../../shared/i18n/format.ts'

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
  formatDateTime(iso, { dateStyle: 'medium', timeStyle: 'medium' })

/** Settings > Audit log: ADMIN browses the audit trail; filters live in the URL. */
/** A password-only LOGIN that still awaits the second factor reads as such. */
function actionLabel(e: AuditLogEntry): string {
  if (e.action === 'LOGIN' && e.details?.mfaPending === true) return i18n.t('settings:AuditLogPage.loginAwaiting2fa')
  return AUDIT_ACTION_LABELS[e.action] ?? e.action
}

export function AuditLogPage() {
  const { t } = useTranslation('settings')
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
    { id: 'time', header: t('AuditLogPage.time'), defaultWidth: 200, hideable: false, render: (e) => formatTime(e.occurredAt) },
    { id: 'user', header: t('AuditLogPage.user'), defaultWidth: 140, render: (e) => e.username },
    { id: 'action', header: t('AuditLogPage.action'), defaultWidth: 220, render: (e) => actionLabel(e) },
    {
      id: 'outcome',
      header: t('AuditLogPage.outcome'),
      defaultWidth: 110,
      render: (e) => <Badge tone={e.outcome === 'FAILURE' ? 'bad' : 'ok'}>{e.outcome === 'FAILURE' ? t('AuditLogPage.failure') : t('AuditLogPage.success')}</Badge>,
    },
    { id: 'target', header: t('AuditLogPage.target'), defaultWidth: 280, render: (e) => <span className="settings__mono">{target(e)}</span> },
  ]

  return (
    <SettingsPage wide title={t('AuditLogPage.auditLog')} subtitle={t('AuditLogPage.whoDidWhatNewestFirst')}>
      <Card>
        <form className="settings__filters" onSubmit={apply}>
          <Select label={t('AuditLogPage.action')} value={draft.action} onChange={(e) => setDraft({ ...draft, action: e.target.value })}>
            <option value="">{t('AuditLogPage.allActions')}</option>
            {Object.entries(AUDIT_ACTION_LABELS).map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </Select>
          <TextField label={t('AuditLogPage.user')} value={draft.username} onChange={(e) => setDraft({ ...draft, username: e.target.value })} />
          <TextField label={t('AuditLogPage.from')} type="date" value={draft.from} onChange={(e) => setDraft({ ...draft, from: e.target.value })} />
          <TextField label={t('AuditLogPage.to')} type="date" value={draft.to} onChange={(e) => setDraft({ ...draft, to: e.target.value })} />
          <div className="settings__filters-actions">
            <Button variant="primary" type="submit">
              {t('AuditLogPage.apply')}
            </Button>
            <Button onClick={reset}>{t('AuditLogPage.reset')}</Button>
          </div>
        </form>
      </Card>

      <ResourceTable
        label={t('AuditLogPage.auditLog2')}
        columns={columns}
        rows={data?.items}
        rowKey={(e) => e.id}
        searchText={(e) => `${e.username} ${e.action} ${target(e)}`}
        defaultSort="time"
        emptyMessage={t('AuditLogPage.noEntriesMatch')}
        isPending={isPending}
        error={isError ? t('AuditLogPage.couldNotLoadTheAudit') : null}
        onRetry={() => void refetch()}
      />
      {data && data.items.length > 0 && <Pager page={pageNumber} pages={pages} onPage={go} />}
    </SettingsPage>
  )
}
