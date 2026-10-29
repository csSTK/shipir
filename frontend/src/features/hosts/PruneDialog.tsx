import { useState } from 'react'
import { Button, Checkbox, Dialog } from '../../shared/ui/index.ts'
import { formatSize } from './format.ts'
import { describePruneError, usePrune, usePrunePreview, type ImagePruneMode, type PruneGroup, type PruneResult } from './prune.ts'
import { useTranslation } from 'react-i18next'
import { Trans } from '../../shared/i18n/Trans.tsx'
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
  const { t } = useTranslation('hosts')
  const rows: [string, PruneResult['containers']][] = [
    ['containers', result.containers],
    ['images', result.images],
    ['volumes', result.volumes],
    ['networks', result.networks],
  ]
  return (
    <div role="status">
      <p className="prune__text">
        <Trans t={t} i18nKey="PruneDialog.cleanupFinishedAboutSizeReclaimed" values={{ size: formatSize(result.reclaimedBytes) }} components={{ strong: <strong /> }} />
      </p>
      <ul className="prune__list">
        {rows.map(([label, r]) => (
          <li key={label}>
            {t('PruneDialog.removedLabelRemoved', { removed: r.removed, label })}{r.skipped > 0 && t('PruneDialog.skippedSkipped', { skipped: r.skipped })}
          </li>
        ))}
      </ul>
    </div>
  )
}

/** Choose what to prune, see the preview, confirm. */
function PruneContent({ hostId, onClose }: { hostId: string; onClose: () => void }) {
  const { t } = useTranslation('hosts')
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
    <>
      {result ? (
        <Result result={result} />
      ) : (
        <>
          <fieldset className="prune__options">
            <legend className="prune__legend">{t('PruneDialog.remove')}</legend>
            <Checkbox className="prune__check" label={t('PruneDialog.stoppedContainers')} checked={containers} onChange={(e) => setContainers(e.target.checked)} />
            <label className="prune__check">
              {t('PruneDialog.images')}
              <select value={images} onChange={(e) => setImages(e.target.value as ImagePruneMode | 'NONE')} aria-label={t('PruneDialog.images')}>
                <option value="NONE">{t('PruneDialog.none')}</option>
                <option value="DANGLING">{t('PruneDialog.danglingOnly')}</option>
                <option value="UNUSED">{t('PruneDialog.allUnused')}</option>
              </select>
            </label>
            <Checkbox className="prune__check" label={t('PruneDialog.unusedVolumes')} checked={volumes} onChange={(e) => setVolumes(e.target.checked)} />
            {volumes && (
              <Checkbox
                className="prune__check prune__check--nested"
                label={t('PruneDialog.includeVolumesOfManagedDeployments')}
                checked={includeManaged}
                onChange={(e) => setIncludeManaged(e.target.checked)}
              />
            )}
            <Checkbox className="prune__check" label={t('PruneDialog.unusedNetworks')} checked={networks} onChange={(e) => setNetworks(e.target.checked)} />
          </fieldset>
          {preview.isPending && <p className="prune__text">{t('PruneDialog.calculatingPreview')}</p>}
          {preview.isError && (
            <p role="alert" className="prune__error">
              {describePruneError(preview.error)}
            </p>
          )}
          {data && (
            <div aria-label={t('PruneDialog.preview')}>
              <ul className="prune__list">
                {containers && <Summary label={t('PruneDialog.containers')} group={data.containers} />}
                {images !== 'NONE' && <Summary label={t('PruneDialog.images2')} group={data.images} />}
                {volumes && <Summary label={t('PruneDialog.volumes')} group={data.volumes} />}
                {networks && <Summary label={t('PruneDialog.networks')} group={data.networks} />}
              </ul>
              {volumes && !includeManaged && data.managedVolumesExcluded > 0 && (
                <p className="prune__note" role="note">
                  {t('PruneDialog.managedVolumesKept', { count: data.managedVolumesExcluded })}
                </p>
              )}
            </div>
          )}
          <p className="prune__note">
            {t('PruneDialog.removingContainersCanFreeMore')}
          </p>
          {error && (
            <p role="alert" className="prune__error">
              {error}
            </p>
          )}
        </>
      )}
      <div className="prune__actions">
        <Button variant="ghost" onClick={onClose}>
          {result ? t('PruneDialog.close') : t('PruneDialog.cancel')}
        </Button>
        {!result && (
          <Button variant="danger" onClick={() => void confirm()} loading={prune.isPending} disabled={nothing || preview.isPending}>
            {prune.isPending ? t('PruneDialog.cleaningUp') : t('PruneDialog.cleanUp')}
          </Button>
        )}
      </div>
    </>
  )
}

/** Dialog shell; the content (and its preview request) exists only while open, so a fresh one mounts per opening. */
export function PruneDialog({ hostId, open = true, onClose }: { hostId: string; open?: boolean; onClose: () => void }) {
  const { t } = useTranslation('hosts')
  return (
    <Dialog open={open} onOpenChange={(next) => !next && onClose()} title={t('PruneDialog.cleanUpUnusedResources')}>
      <PruneContent hostId={hostId} onClose={onClose} />
    </Dialog>
  )
}
