import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Globe, Pencil, Plus, Trash2 } from 'lucide-react'
import { Badge, Button, Card, ConfirmDialog, EmptyState, ResourceTable, Skeleton, type BadgeTone, type ResourceColumn, type RowActions } from '../../../shared/ui/index.ts'
import { describeRouteError } from './routeErrors.ts'
import { PublishDialog } from './PublishDialog.tsx'
import { useRouteStatuses, useRoutes, useUnpublishRoute, type CertificateStatusName, type ProxyRoute, type RouteCertificateStatus } from './api.ts'
import './RoutesList.css'

const STATUS_TONE: Record<CertificateStatusName, BadgeTone> = { ISSUED: 'ok', PENDING: 'warn', FAILED: 'bad', INTERNAL: 'info', CUSTOM: 'info' }

function StatusCell({ status }: { status: RouteCertificateStatus | undefined }) {
  const { t } = useTranslation('domains')
  if (!status) return <span className="routes__muted">–</span>
  return (
    <span className="routes__status">
      <Badge tone={STATUS_TONE[status.status]}>{t(`RoutesList.status.${status.status}`)}</Badge>
      {status.status === 'FAILED' && status.lastError && <span className="routes__error">{status.lastError}</span>}
    </span>
  )
}

/** The domains published through the managed proxy. ADMIN and OPERATOR publish, edit and unpublish; others read. */
export function RoutesList({ hostId, canPublish }: { hostId: string; canPublish: boolean }) {
  const { t } = useTranslation('domains')
  const { data, isPending, isError, refetch } = useRoutes(hostId)
  const statuses = useRouteStatuses(hostId)
  const unpublish = useUnpublishRoute(hostId)
  const [dialog, setDialog] = useState<{ route?: ProxyRoute } | null>(null)
  const [removing, setRemoving] = useState<ProxyRoute | null>(null)
  const [removeError, setRemoveError] = useState<string | null>(null)
  const statusByHost = new Map((statuses.data ?? []).map((s) => [s.hostname, s]))

  const columns: ResourceColumn<ProxyRoute>[] = [
    {
      id: 'hostname',
      header: t('RoutesList.domain'),
      render: (r) => (
        <span className="routes__domain">
          {r.hostname.startsWith('*.') ? (
            <strong className="routes__host">{r.hostname}</strong>
          ) : (
            <a className="routes__host routes__link" href={`https://${r.hostname}`} target="_blank" rel="noopener noreferrer">
              {r.hostname}
            </a>
          )}
          {r.advancedActive && (
            <Badge tone="accent" dot={false}>
              {t('RoutesList.advanced')}
            </Badge>
          )}
        </span>
      ),
      sortValue: (r) => r.hostname,
      defaultWidth: 280,
      hideable: false,
    },
    {
      id: 'container',
      header: t('RoutesList.container'),
      render: (r) => (
        <span className="routes__container">
          {r.containerName}
          {r.containerState === null && <Badge tone="warn">{t('RoutesList.containerMissing')}</Badge>}
        </span>
      ),
      sortValue: (r) => r.containerName,
      defaultWidth: 220,
    },
    { id: 'port', header: t('RoutesList.port'), render: (r) => r.targetPort, sortValue: (r) => r.targetPort, defaultWidth: 90 },
    { id: 'certificate', header: t('RoutesList.certificate'), render: (r) => <StatusCell status={statusByHost.get(r.hostname)} />, defaultWidth: 240 },
  ]

  const rowActions = canPublish
    ? (r: ProxyRoute): RowActions => ({
        primary: [{ label: t('RoutesList.edit'), icon: Pencil, onSelect: () => setDialog({ route: r }) }],
        menu: [{ label: t('RoutesList.unpublish'), icon: <Trash2 size={16} strokeWidth={1.75} />, danger: true, onSelect: () => openRemove(r) }],
      })
    : undefined

  function openRemove(route: ProxyRoute) {
    setRemoveError(null)
    setRemoving(route)
  }

  function confirmRemove() {
    if (!removing) return
    unpublish.mutate(removing.id, {
      onSuccess: () => setRemoving(null),
      onError: (e) => setRemoveError(describeRouteError(e).message),
    })
  }

  const publishButton = canPublish ? (
    <Button variant="primary" onClick={() => setDialog({})}>
      <Plus size={16} strokeWidth={2} aria-hidden="true" />
      {t('RoutesList.publish')}
    </Button>
  ) : undefined

  let body
  if (isPending) {
    body = (
      <div role="status" aria-label={t('RoutesList.loading')} aria-busy="true">
        <Skeleton height="6rem" />
      </div>
    )
  } else if (isError) {
    body = (
      <div className="routes__failed">
        <p role="alert" className="routes__failed-text">
          {t('RoutesList.loadFailed')}
        </p>
        <div>
          <Button onClick={() => void refetch()}>{t('DomainsPage.retry')}</Button>
        </div>
      </div>
    )
  } else if (data.length === 0) {
    body = <EmptyState icon={Globe} title={t('DomainsPage.noDomains')} description={canPublish ? t('RoutesList.emptyWriter') : t('RoutesList.emptyReader')} action={publishButton} />
  } else {
    body = (
      <ResourceTable
        label={t('RoutesList.tableLabel')}
        id="proxy-routes"
        columns={columns}
        rows={data}
        rowKey={(r) => r.id}
        searchText={(r) => `${r.hostname} ${r.containerName}`}
        defaultSort="hostname"
        emptyMessage={t('DomainsPage.noDomains')}
        rowActions={rowActions}
        rowLabel={(r) => r.hostname}
      />
    )
  }

  return (
    <Card className="routes">
      <div className="routes__head">
        <div className="routes__titles">
          <h2 className="routes__title">{t('RoutesList.title')}</h2>
          <p className="routes__intro">{t('RoutesList.intro')}</p>
        </div>
        {data && data.length > 0 && publishButton}
      </div>
      {body}
      {dialog && <PublishDialog hostId={hostId} route={dialog.route} onClose={() => setDialog(null)} />}
      <ConfirmDialog
        open={removing !== null}
        onOpenChange={(open) => !open && setRemoving(null)}
        title={t('RoutesList.unpublishTitle', { name: removing?.hostname ?? '' })}
        confirmLabel={t('RoutesList.unpublish')}
        danger
        loading={unpublish.isPending}
        onConfirm={confirmRemove}
        error={removeError}
      >
        {t('RoutesList.unpublishText')}
      </ConfirmDialog>
    </Card>
  )
}
