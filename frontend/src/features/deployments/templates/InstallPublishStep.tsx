import { useEffect, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router-dom'
import { Globe } from 'lucide-react'
import { apiFetch } from '../../../shared/api/client.ts'
import { Button } from '../../../shared/ui/index.ts'
import { PublishDialog } from '../../domains/index.ts'
import { matchContainer } from './install.ts'

const POLL_MS = 2000
/** The deploy runs async; after this long without a container something went wrong and polling stops. */
export const GIVE_UP_MS = 60_000

/** Waits for the Compose container of the new deployment to show up in the publishable containers (the deploy runs async). */
function useMainContainer(hostId: string, project: string, service: string, enabled: boolean, giveUp: boolean) {
  return useQuery({
    queryKey: ['hosts', hostId, 'install-publish', project, service],
    queryFn: async () => matchContainer(await apiFetch<{ containerName: string }[]>(`/hosts/${encodeURIComponent(hostId)}/proxy/routes/candidates`), project, service),
    enabled: enabled && !giveUp,
    refetchInterval: (q) => (q.state.data ? false : POLL_MS),
  })
}

/**
 * Optional last step of a catalog install: publish the app under a domain (managed proxy ready) or a hint where to set the
 * proxy up. Skipping, or finishing the publish, continues to the deployment.
 */
export function InstallPublishStep({
  hostId,
  proxy,
  project,
  service,
  port,
  onContinue,
}: {
  hostId: string
  proxy: 'ready' | 'notRunning' | 'missing'
  project: string
  service: string
  port: number
  onContinue: () => void
}) {
  const { t } = useTranslation('deployments')
  const [publishing, setPublishing] = useState(false)
  const proxyReady = proxy === 'ready'
  const [giveUp, setGiveUp] = useState(false)
  const container = useMainContainer(hostId, project, service, proxyReady, giveUp)
  const name = container.data ?? null
  useEffect(() => {
    if (!proxyReady || name) return
    const timer = setTimeout(() => setGiveUp(true), GIVE_UP_MS)
    return () => clearTimeout(timer)
  }, [proxyReady, name])

  return (
    <div className="ui-form tp-publish">
      <span className="tp-publish__icon" aria-hidden="true">
        <Globe size={20} strokeWidth={1.75} />
      </span>
      <p className="tp-publish__title">{t('InstallPublishStep.installed')}</p>
      {proxyReady ? (
        <>
          <p className="ui-hint">{name ? t('InstallPublishStep.offer') : giveUp ? t('InstallPublishStep.slow') : t('InstallPublishStep.waiting')}</p>
          <div className="ui-dialog-actions">
            <Button variant="ghost" onClick={onContinue}>
              {t('InstallPublishStep.skip')}
            </Button>
            <Button variant="primary" disabled={name === null} onClick={() => setPublishing(true)}>
              {t('InstallPublishStep.publish')}
            </Button>
          </div>
          {publishing && name && <PublishDialog hostId={hostId} containerName={name} port={port} onClose={() => setPublishing(false)} onDone={onContinue} />}
        </>
      ) : (
        <>
          <p className="ui-hint">{proxy === 'notRunning' ? t('InstallPublishStep.proxyNotRunning') : t('InstallPublishStep.noProxy')}</p>
          <div className="ui-dialog-actions">
            <Button variant="secondary" asChild>
              <Link to={`/environments/${hostId}/domains`}>{proxy === 'notRunning' ? t('InstallPublishStep.openDomains') : t('InstallPublishStep.setUpDomains')}</Link>
            </Button>
            <Button variant="primary" onClick={onContinue}>
              {t('InstallPublishStep.openDeployment')}
            </Button>
          </div>
        </>
      )}
    </div>
  )
}
