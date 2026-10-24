import { useSyncExternalStore } from 'react'
import { useDeploymentVersionDetail } from '../api.ts'
import { describeDeploymentError } from '../deploymentErrors.ts'
import { YamlDiffEditor } from '../editor/index.ts'
import './VersionDiff.css'

const NARROW = '(max-width: 720px)'
const narrowQuery = typeof window !== 'undefined' && window.matchMedia ? window.matchMedia(NARROW) : null
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
  const narrow = useSyncExternalStore(subscribeNarrow, () => narrowQuery?.matches ?? false)
  const target = useDeploymentVersionDetail(hostId, deploymentId, versionId)
  const baselineId = target.data?.baselineVersionId ?? null
  const baseline = useDeploymentVersionDetail(hostId, deploymentId, baselineId)

  const error = target.error ?? baseline.error
  if (error) {
    return (
      <p role="alert" className="vd-note vd-note--error">
        {describeDeploymentError(error, 'Could not load the changes.')} You can still continue.
      </p>
    )
  }
  if (target.isPending || (baselineId && baseline.isPending)) {
    return (
      <p role="status" className="vd-note">
        Loading changes…
      </p>
    )
  }
  if (!baselineId || baselineId === versionId || !baseline.data || !target.data) {
    return <p className="vd-note">There is no other deployed version to compare with.</p>
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
    return <p className="vd-note">No changes compared with the running version.</p>
  }

  return (
    <div className="vd">
      <section className="vd-section" aria-label="Compose file changes">
        <h3 className="vd-heading">Compose file</h3>
        {yamlSame ? (
          <p className="vd-note">Unchanged.</p>
        ) : (
          <>
            <div className="vd-legend" aria-hidden="true">
              <span>Running now</span>
              <span>After this action</span>
            </div>
            <YamlDiffEditor original={before.yaml} modified={after.yaml} label="Compose file changes" sideBySide={!narrow} />
          </>
        )}
      </section>
      <section className="vd-section" aria-label="Environment changes">
        <h3 className="vd-heading">Environment variables</h3>
        {envSame ? (
          <p className="vd-note">Unchanged. Values are never shown.</p>
        ) : (
          <ul className="vd-env">
            {added.map((k) => (
              <li key={`a-${k}`} className="vd-env__item vd-env__item--added">
                <span className="vd-env__tag">Added</span>
                <code>{k}</code>
              </li>
            ))}
            {changed.map((k) => (
              <li key={`c-${k}`} className="vd-env__item vd-env__item--changed">
                <span className="vd-env__tag">Changed</span>
                <code>{k}</code>
              </li>
            ))}
            {removed.map((k) => (
              <li key={`r-${k}`} className="vd-env__item vd-env__item--removed">
                <span className="vd-env__tag">Removed</span>
                <code>{k}</code>
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  )
}
