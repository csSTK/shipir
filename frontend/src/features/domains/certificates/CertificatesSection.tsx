import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Plus, RefreshCw, ShieldCheck, Trash2 } from 'lucide-react'
import { Badge, Button, Card, ConfirmDialog, EmptyState, ResourceTable, Skeleton, type ResourceColumn, type RowActions } from '../../../shared/ui/index.ts'
import { formatDate } from '../../../shared/i18n/index.ts'
import { useCertificates, useDeleteCertificate, type ProxyCertificate } from './api.ts'
import { CertificateDialog } from './CertificateDialog.tsx'
import { describeCertificateError } from './certificateErrors.ts'
import './CertificatesSection.css'

const WARN_DAYS = 30
const DAY_MS = 24 * 60 * 60 * 1000

/** Days until `notAfter`, rounded up (0.2 days left is still "1 day left"); 0 or less once expired. */
function daysLeft(notAfter: string): number {
  return Math.ceil((new Date(notAfter).getTime() - Date.now()) / DAY_MS)
}

function Expiry({ notAfter }: { notAfter: string }) {
  const { t } = useTranslation('domains')
  const days = daysLeft(notAfter)
  if (days <= 0) return <Badge tone="bad">{t('CertificatesSection.expired')}</Badge>
  if (days < WARN_DAYS) return <Badge tone="warn">{t('CertificatesSection.daysLeft', { count: days })}</Badge>
  return <span className="certs__date">{formatDate(notAfter)}</span>
}

/** Certificates a user uploaded for the managed proxy. ADMINs upload, renew and delete; others read. */
export function CertificatesSection({ hostId, isAdmin }: { hostId: string; isAdmin: boolean }) {
  const { t } = useTranslation('domains')
  const { data, isPending, isError, refetch } = useCertificates(hostId)
  const remove = useDeleteCertificate(hostId)
  const [dialog, setDialog] = useState<{ renewing: ProxyCertificate | null } | null>(null)
  const [deleting, setDeleting] = useState<ProxyCertificate | null>(null)
  const [deleteError, setDeleteError] = useState<string | null>(null)

  const columns: ResourceColumn<ProxyCertificate>[] = [
    { id: 'name', header: t('CertificatesSection.name'), render: (c) => <strong className="certs__name">{c.name}</strong>, sortValue: (c) => c.name, defaultWidth: 160 },
    { id: 'domains', header: t('CertificatesSection.domains'), render: (c) => c.domains.join(', '), defaultWidth: 240 },
    { id: 'expires', header: t('CertificatesSection.expires'), render: (c) => <Expiry notAfter={c.notAfter} />, sortValue: (c) => new Date(c.notAfter).getTime(), defaultWidth: 150 },
    {
      id: 'usedBy',
      header: t('CertificatesSection.usedBy'),
      render: (c) => (c.usedByRoutes.length ? c.usedByRoutes.join(', ') : <span className="certs__muted">{t('CertificatesSection.unused')}</span>),
      defaultWidth: 200,
    },
  ]

  const rowActions = isAdmin
    ? (c: ProxyCertificate): RowActions => ({
        primary: [{ label: t('CertificatesSection.renew'), icon: RefreshCw, onSelect: () => setDialog({ renewing: c }) }],
        menu: [{ label: t('CertificatesSection.delete'), icon: <Trash2 size={16} strokeWidth={1.75} />, danger: true, onSelect: () => openDelete(c) }],
      })
    : undefined

  function openDelete(cert: ProxyCertificate) {
    setDeleteError(null)
    setDeleting(cert)
  }

  function confirmDelete() {
    if (!deleting) return
    remove.mutate(deleting.id, {
      onSuccess: () => setDeleting(null),
      onError: (e) => setDeleteError(describeCertificateError(e).message),
    })
  }

  const uploadButton = isAdmin ? (
    <Button variant="primary" onClick={() => setDialog({ renewing: null })}>
      <Plus size={16} strokeWidth={2} aria-hidden="true" />
      {t('CertificatesSection.upload')}
    </Button>
  ) : undefined

  let body
  if (isPending) {
    body = (
      <div role="status" aria-label={t('CertificatesSection.loading')} aria-busy="true">
        <Skeleton height="6rem" />
      </div>
    )
  } else if (isError) {
    body = (
      <div className="certs__error">
        <p role="alert" className="certs__error-text">
          {t('CertificatesSection.loadFailed')}
        </p>
        <div>
          <Button onClick={() => void refetch()}>{t('DomainsPage.retry')}</Button>
        </div>
      </div>
    )
  } else if (data.length === 0) {
    body = <EmptyState icon={ShieldCheck} title={t('CertificatesSection.emptyTitle')} description={isAdmin ? t('CertificatesSection.emptyAdmin') : t('CertificatesSection.emptyReadOnly')} action={uploadButton} />
  } else {
    body = (
      <ResourceTable
        label={t('CertificatesSection.tableLabel')}
        id="proxy-certificates"
        columns={columns}
        rows={data}
        rowKey={(c) => c.id}
        searchText={(c) => `${c.name} ${c.domains.join(' ')} ${c.usedByRoutes.join(' ')}`}
        defaultSort="name"
        emptyMessage={t('CertificatesSection.emptyTitle')}
        rowActions={rowActions}
        rowLabel={(c) => c.name}
      />
    )
  }

  return (
    <Card className="certs">
      <div className="certs__head">
        <div className="certs__titles">
          <h2 className="certs__title">{t('CertificatesSection.title')}</h2>
          <p className="certs__intro">{t('CertificatesSection.intro')}</p>
        </div>
        {data && data.length > 0 && uploadButton}
      </div>
      {body}
      {dialog && <CertificateDialog hostId={hostId} renewing={dialog.renewing} onClose={() => setDialog(null)} />}
      <ConfirmDialog
        open={deleting !== null}
        onOpenChange={(open) => !open && setDeleting(null)}
        title={t('CertificatesSection.deleteTitle', { name: deleting?.name ?? '' })}
        confirmLabel={t('CertificatesSection.delete')}
        danger
        loading={remove.isPending}
        onConfirm={confirmDelete}
        error={deleteError}
      >
        {t('CertificatesSection.deleteText')}
      </ConfirmDialog>
    </Card>
  )
}
