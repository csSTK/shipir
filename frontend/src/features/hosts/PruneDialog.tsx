import { useId, useState } from 'react'
import { DeploymentDialog } from '../deployments/DeploymentDialog.tsx'
import { formatSize } from './format.ts'
import { describePruneError, usePrune, usePrunePreview, type ImagePruneMode, type PruneGroup, type PruneResult } from './prune.ts'
import './PruneDialog.css'

function Summary({ label, group }: { label: string; group: PruneGroup }) {
  return (
    <li>
      <strong>{group.count}</strong> {label}
      {group.count > 0 && group.sizeBytes > 0 && <> ({formatSize(group.sizeBytes)})</>}
    </li>
  )
}

function Result({ result }: { result: PruneResult }) {
  const rows: [string, PruneResult['containers']][] = [
    ['containers', result.containers],
    ['images', result.images],
    ['volumes', result.volumes],
    ['networks', result.networks],
  ]
  return (
    <div role="status">
      <p className="sk-dialog__text">
        Cleanup finished. About <strong>{formatSize(result.reclaimedBytes)}</strong> reclaimed (estimate).
      </p>
      <ul className="prune__list">
        {rows.map(([label, r]) => (
          <li key={label}>
            {r.removed} {label} removed{r.skipped > 0 && `, ${r.skipped} skipped`}
          </li>
        ))}
      </ul>
    </div>
  )
}

/** "Clean up" dialog: choose what to prune, see the preview, confirm. */
export function PruneDialog({ hostId, onClose }: { hostId: string; onClose: () => void }) {
  const titleId = useId()
  const [containers, setContainers] = useState(true)
  const [images, setImages] = useState<ImagePruneMode | 'NONE'>('DANGLING')
  const [volumes, setVolumes] = useState(false)
  const [networks, setNetworks] = useState(true)
  const [includeManaged, setIncludeManaged] = useState(false)
  const [result, setResult] = useState<PruneResult | null>(null)
  const [error, setError] = useState<string | null>(null)

  const preview = usePrunePreview(hostId, images === 'NONE' ? 'DANGLING' : images, includeManaged)
  const prune = usePrune(hostId)
  const nothing = !containers && images === 'NONE' && !volumes && !networks

  async function confirm() {
    setError(null)
    try {
      setResult(
        await prune.mutateAsync({
          containers,
          images: images === 'NONE' ? null : images,
          volumes,
          networks,
          includeManagedVolumes: volumes && includeManaged,
        }),
      )
    } catch (e) {
      setError(describePruneError(e))
    }
  }

  const data = preview.data
  return (
    <DeploymentDialog title="Clean up unused resources" titleId={titleId} onClose={onClose}>
      {result ? (
        <Result result={result} />
      ) : (
        <>
          <fieldset className="prune__options">
            <legend className="prune__legend">Remove</legend>
            <label className="prune__check">
              <input type="checkbox" checked={containers} onChange={(e) => setContainers(e.target.checked)} /> Stopped containers
            </label>
            <label className="prune__check">
              Images
              <select value={images} onChange={(e) => setImages(e.target.value as ImagePruneMode | 'NONE')} aria-label="Images">
                <option value="NONE">None</option>
                <option value="DANGLING">Dangling only</option>
                <option value="UNUSED">All unused</option>
              </select>
            </label>
            <label className="prune__check">
              <input type="checkbox" checked={volumes} onChange={(e) => setVolumes(e.target.checked)} /> Unused volumes
            </label>
            {volumes && (
              <label className="prune__check prune__check--nested">
                <input type="checkbox" checked={includeManaged} onChange={(e) => setIncludeManaged(e.target.checked)} /> Include volumes of
                managed deployments
              </label>
            )}
            <label className="prune__check">
              <input type="checkbox" checked={networks} onChange={(e) => setNetworks(e.target.checked)} /> Unused networks
            </label>
          </fieldset>
          {preview.isPending && <p className="sk-dialog__text">Calculating preview…</p>}
          {preview.isError && (
            <p role="alert" className="sk-error">
              {describePruneError(preview.error)}
            </p>
          )}
          {data && (
            <div aria-label="Preview">
              <ul className="prune__list">
                {containers && <Summary label="containers" group={data.containers} />}
                {images !== 'NONE' && <Summary label="images" group={data.images} />}
                {volumes && <Summary label="volumes" group={data.volumes} />}
                {networks && <Summary label="networks" group={data.networks} />}
              </ul>
              {volumes && !includeManaged && data.managedVolumesExcluded > 0 && (
                <p className="prune__note" role="note">
                  {data.managedVolumesExcluded} volume(s) of managed deployments are kept.
                </p>
              )}
            </div>
          )}
          <p className="prune__note">
            Removing containers can free more images and volumes than listed. Removed data cannot be restored.
          </p>
          {error && (
            <p role="alert" className="sk-error">
              {error}
            </p>
          )}
        </>
      )}
      <div className="sk-dialog__actions">
        <button type="button" className="sk-btn sk-btn--plain" onClick={onClose}>
          {result ? 'Close' : 'Cancel'}
        </button>
        {!result && (
          <button
            type="button"
            className="sk-btn sk-btn--danger"
            onClick={() => void confirm()}
            disabled={nothing || prune.isPending || preview.isPending}
          >
            {prune.isPending ? 'Cleaning up…' : 'Clean up'}
          </button>
        )}
      </div>
    </DeploymentDialog>
  )
}
