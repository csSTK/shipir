import { useEffect, useState } from 'react'
import { Link, useLocation, useParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { AlertTriangle, Lock } from 'lucide-react'
import { Badge, Button, Card, PageHeader, Skeleton, Switch, type BadgeTone } from '../../shared/ui/index.ts'
import { ApiError } from '../../shared/api/client.ts'
import { useCurrentUser } from '../auth/index.ts'
import { DeployOutput } from '../deployments/deploy-output/DeployOutput.tsx'
import { useManagedProxy, useRefreshProxy, useSetupProxy, type ProxyInstance, type SetupBlockedReason } from './api.ts'
import { describeProxyError } from './domainErrors.ts'
import { useAdvancedMode } from './advancedMode.ts'
import { ProxySettingsPanel } from './ProxySettingsPanel.tsx'
import { CERTIFICATES_ID } from './routes/PublishDialog.tsx'
import { CertificatesSection } from './certificates/CertificatesSection.tsx'
import { RoutesList } from './routes/RoutesList.tsx'
import { ProxySetupCard } from './ProxySetupCard.tsx'
import './DomainsPage.css'

const STATUS_TONE: Record<string, BadgeTone> = { RUNNING: 'ok', STOPPED: 'muted', ERROR: 'bad' }

function Blocked({ reason, hostId }: { reason: SetupBlockedReason; hostId: string }) {
  const { t } = useTranslation('domains')
  return (
    <Card className="domains__panel">
      <span className="domains__icon domains__icon--warn" aria-hidden="true">
        <AlertTriangle size={20} strokeWidth={1.75} />
      </span>
      <h2 className="domains__panel-title">{t('DomainsPage.blockedTitle')}</h2>
      <p className="domains__panel-text">{reason === 'EXISTING_PROXY' ? t('DomainsPage.blockedExisting') : t('DomainsPage.blockedPorts')}</p>
      <div>
        <Button asChild>
          <Link to={`/environments/${hostId}/containers`}>{t('DomainsPage.openContainers')}</Link>
        </Button>
      </div>
    </Card>
  )
}

function ReadOnlyNotice() {
  const { t } = useTranslation('domains')
  return (
    <Card className="domains__panel">
      <span className="domains__icon" aria-hidden="true">
        <Lock size={20} strokeWidth={1.75} />
      </span>
      <h2 className="domains__panel-title">{t('DomainsPage.notSetUp')}</h2>
      <p className="domains__panel-text">{t('DomainsPage.adminOnly')}</p>
    </Card>
  )
}

function Ready({ instance, hostId, isAdmin, canPublish }: { instance: ProxyInstance; hostId: string; isAdmin: boolean; canPublish: boolean }) {
  const { t } = useTranslation('domains')
  const [advanced] = useAdvancedMode()
  const { hash } = useLocation()
  useEffect(() => {
    if (hash === `#${CERTIFICATES_ID}`) document.getElementById(CERTIFICATES_ID)?.scrollIntoView({ block: 'start' })
  }, [hash])
  const status = instance.deploymentStatus
  return (
    <>
      <Card className="domains__panel">
        <div className="domains__ready-head">
          <h2 className="domains__panel-title">{instance.type === 'TRAEFIK' ? t('ProxySetupCard.traefik') : t('ProxySetupCard.caddy')}</h2>
          {status ? <Badge tone={STATUS_TONE[status] ?? 'muted'}>{t(`DomainsPage.status.${status}`)}</Badge> : <Badge tone="warn">{t('DomainsPage.status.MISSING')}</Badge>}
        </div>
        <p className="domains__panel-text">{t('DomainsPage.readyText')}</p>
        {status && (
          <div>
            <Button asChild>
              <Link to={`/environments/${hostId}/deployments/${instance.deploymentId}`}>{t('DomainsPage.openDeployment')}</Link>
            </Button>
          </div>
        )}
      </Card>
      <RoutesList hostId={hostId} canPublish={canPublish} />
      {advanced && isAdmin && <ProxySettingsPanel hostId={hostId} instance={instance} />}
      <div id={CERTIFICATES_ID} className="domains__anchor">
        <CertificatesSection hostId={hostId} isAdmin={isAdmin} />
      </div>
    </>
  )
}

/** `/environments/:id/domains`: set up the managed reverse proxy and (later) publish containers on domains. */
export function DomainsPage() {
  const { t } = useTranslation('domains')
  const { id = '' } = useParams()
  const { data: user } = useCurrentUser()
  const { data, isPending, isError, refetch } = useManagedProxy(id)
  const setup = useSetupProxy(id)
  const refresh = useRefreshProxy(id)
  const [runId, setRunId] = useState<string | null>(null)
  const [runFinished, setRunFinished] = useState(false)
  const [runFailed, setRunFailed] = useState(false)
  const [advanced, setAdvanced, advancedReady] = useAdvancedMode()
  const isAdmin = user?.role === 'ADMIN'
  const canPublish = isAdmin || user?.role === 'OPERATOR'

  let body
  if (isPending) {
    body = (
      <div role="status" aria-label={t('DomainsPage.loading')} aria-busy="true" className="domains__stack">
        <Skeleton height="10rem" />
      </div>
    )
  } else if (isError) {
    body = (
      <div className="domains__stack">
        <p role="alert" className="domains__error">
          {t('DomainsPage.loadFailed')}
        </p>
        <div>
          <Button onClick={() => void refetch()}>{t('DomainsPage.retry')}</Button>
        </div>
      </div>
    )
  } else if (runId) {
    body = (
      <>
        <DeployOutput
          title={t('DomainsPage.setupRunTitle')}
          runId={runId}
          onFinished={(result) => {
            setRunFinished(true)
            setRunFailed(result === 'FAILED')
            void refresh()
          }}
        />
        {runFinished && runFailed && (
          <p role="alert" className="domains__error">
            {t('DomainsPage.setupFailed')}
          </p>
        )}
        {runFinished && data.instance && <Ready instance={data.instance} hostId={id} isAdmin={isAdmin} canPublish={canPublish} />}
      </>
    )
  } else if (data.instance) {
    body = <Ready instance={data.instance} hostId={id} isAdmin={isAdmin} canPublish={canPublish} />
  } else if (data.setupBlockedReason) {
    body = <Blocked reason={data.setupBlockedReason} hostId={id} />
  } else if (!isAdmin) {
    body = <ReadOnlyNotice />
  } else {
    body = (
      <ProxySetupCard
        busy={setup.isPending}
        error={setup.isError ? describeProxyError(setup.error) : null}
        onSubmit={(request) =>
          setup.mutate(request, {
            onSuccess: (result) => (result.runId ? setRunId(result.runId) : void refresh()),
            // The server knows better (a proxy appeared or ports got busy): show the state it reports.
            onError: (error) => {
              if (error instanceof ApiError && (error.code === 'PROXY_EXISTS' || error.code === 'PROXY_SETUP_BLOCKED')) void refresh()
            },
          })
        }
      />
    )
  }

  return (
    <section className="domains">
      <PageHeader
        title={t('DomainsPage.title')}
        subtitle={t('DomainsPage.subtitle')}
        actions={data?.instance && advancedReady ? <Switch label={t('DomainsPage.advancedMode')} checked={advanced} onChange={(e) => setAdvanced(e.target.checked)} /> : undefined}
      />
      {body}
    </section>
  )
}
