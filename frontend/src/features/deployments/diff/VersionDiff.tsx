import { useSyncExternalStore } from 'react'
import { useDeploymentVersionDetail } from '../api.ts'
import { describeDeploymentError } from '../deploymentErrors.ts'
import { YamlDiffEditor } from '../editor/index.ts'
import { useTranslation } from 'react-i18next'
import { Trans } from '../../../shared/i18n/Trans.tsx'
import './VersionDiff.css'
import { PHONE_QUERY } from '../../../shared/lib/breakpoints.ts'

const narrowQuery = typeof window !== 'undefined' && window.matchMedia ? window.matchMedia(PHONE_QUERY) : null
function subscribeNarrow(cb: () => void) {
  narrowQuery?.addEventListener('change', cb)
  return () => narrowQuery?.removeEventListener('change', cb)
}

interface Props {
  hostId: string
  deploymentId: string
  /** The version that would run after the action (rollback target or draft). */
  versionId: string
}

/** Diff of one version against the newest deployed version (what runs now): YAML plus env key changes, never values. */
export function VersionDiff({ hostId, deploymentId, versionId }: Props) {
  const { t } = useTranslation('deployments')
  const narrow = useSyncExternalStore(subscribeNarrow, () => narrowQuery?.matches ?? false)
  const target = useDeploymentVersionDetail(hostId, deploymentId, versionId)
  const baselineId = target.data?.baselineVersionId ?? null
  const baseline = useDeploymentVersionDetail(hostId, deploymentId, baselineId)

  const error = target.error ?? baseline.error
  if (error) {
    return (
      <p role="alert" className="vd-note vd-note--error">
        {t('VersionDiff.deploymenterrorYouCanStillContinue', { deploymentError: describeDeploymentError(error, t('VersionDiff.couldNotLoadTheChanges')) })}
      </p>
    )
  }
  if (target.isPending || (baselineId && baseline.isPending)) {
    return (
      <p role="status" className="vd-note">
        {t('VersionDiff.loadingChanges')}
      </p>
    )
  }
  if (!baselineId || baselineId === versionId || !baseline.data || !target.data) {
    return <p className="vd-note">{t('VersionDiff.thereIsNoOtherDeployed')}</p>
  }

  const before = baseline.data
  const after = target.data
  const beforeKeys = new Set(before.env.map((e) => e.key))
  const added = after.env.filter((e) => !beforeKeys.has(e.key)).map((e) => e.key)
  const changed = after.env.filter((e) => e.changed && beforeKeys.has(e.key)).map((e) => e.key)
  const removed = after.removedKeys
  const envSame = added.length + changed.length + removed.length === 0
  const yamlSame = before.yaml === after.yaml

  if (yamlSame && envSame) {
    return <p className="vd-note">{t('VersionDiff.noChangesComparedWithThe')}</p>
  }

  return (
    <div className="vd">
      <section className="vd-section" aria-label={t('VersionDiff.composeFileChanges')}>
        <h3 className="vd-heading">{t('VersionDiff.composeFile')}</h3>
        {yamlSame ? (
          <p className="vd-note">{t('VersionDiff.unchanged')}</p>
        ) : (
          <>
            <div className="vd-legend" aria-hidden="true">
              <Trans t={t} i18nKey="VersionDiff.runningNowAfterThisAction" components={{ span: <span /> }} />
            </div>
            <YamlDiffEditor original={before.yaml} modified={after.yaml} label={t('VersionDiff.composeFileChanges')} sideBySide={!narrow} />
          </>
        )}
      </section>
      <section className="vd-section" aria-label={t('VersionDiff.environmentChanges')}>
        <h3 className="vd-heading">{t('VersionDiff.environmentVariables')}</h3>
        {envSame ? (
          <p className="vd-note">{t('VersionDiff.unchangedValuesAreNeverShown')}</p>
        ) : (
          <ul className="vd-env">
            {added.map((k) => (
              <li key={`a-${k}`} className="vd-env__item vd-env__item--added">
                <Trans t={t} i18nKey="VersionDiff.addedK" values={{ k }} components={{ span: <span className="vd-env__tag" />, code: <code /> }} />
              </li>
            ))}
            {changed.map((k) => (
              <li key={`c-${k}`} className="vd-env__item vd-env__item--changed">
                <Trans t={t} i18nKey="VersionDiff.changedK" values={{ k }} components={{ span: <span className="vd-env__tag" />, code: <code /> }} />
              </li>
            ))}
            {removed.map((k) => (
              <li key={`r-${k}`} className="vd-env__item vd-env__item--removed">
                <Trans t={t} i18nKey="VersionDiff.removedK" values={{ k }} components={{ span: <span className="vd-env__tag" />, code: <code /> }} />
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  )
}
