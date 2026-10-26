import { RefreshCw } from 'lucide-react'
import { ApiError } from '../../shared/api/client.ts'
import { Button, Card, IconButton, Skeleton } from '../../shared/ui/index.ts'
import { countOf, formatSize, type CountNoun } from './format.ts'
import { useDiskUsage, type DiskCategory, type DiskUsage, type LargestCategory, type LargestItem } from './diskUsage.ts'
import { useTranslation } from 'react-i18next'
import { Trans } from '../../shared/i18n/Trans.tsx'
import i18n from 'i18next'
import './DiskUsageCard.css'

type CategoryKey = 'images' | 'containers' | 'volumes' | 'buildCache'

const CATEGORIES: { key: CategoryKey; label: string; noun: CountNoun }[] = [
  { key: 'images', get label() { return i18n.t('hosts:DiskUsageCard.images') }, noun: 'image' },
  { key: 'containers', get label() { return i18n.t('hosts:DiskUsageCard.containers') }, noun: 'container' },
  { key: 'volumes', get label() { return i18n.t('hosts:DiskUsageCard.volumes') }, noun: 'volume' },
  { key: 'buildCache', get label() { return i18n.t('hosts:DiskUsageCard.buildCache') }, noun: 'entry' },
]

const LARGEST_LABEL: Record<LargestCategory, string> = {
  get IMAGE() { return i18n.t('hosts:DiskUsageCard.image') },
  get CONTAINER() { return i18n.t('hosts:DiskUsageCard.container') },
  get VOLUME() { return i18n.t('hosts:DiskUsageCard.volume') },
  get BUILD_CACHE() { return i18n.t('hosts:DiskUsageCard.buildCache') },
}

const size = (bytes: number | null) => (bytes === null ? i18n.t('hosts:DiskUsageCard.unknown') : formatSize(bytes))

/** Sum of the reported values; unreported ones count as zero. */
const sumKnown = (values: (number | null)[]) => values.reduce<number>((total, value) => total + (value ?? 0), 0)

/** Accessible summary of the bar, which is a picture for sighted users only. */
function summarise(usage: DiskUsage): string {
  const parts = CATEGORIES.map(({ key, label }) => `${label} ${size(usage[key].totalSize)}`)
  return i18n.t('hosts:DiskUsageCard.diskUsageJoin', { summary: parts.join(', ') })
}

function Bar({ usage, total }: { usage: DiskUsage; total: number }) {
  return (
    <div className="disk__bar" role="img" aria-label={summarise(usage)}>
      {total > 0 &&
        CATEGORIES.map(({ key }) => {
          const bytes = usage[key].totalSize ?? 0
          return bytes > 0 ? (
            <span key={key} className={`disk__seg disk__seg--${key}`} style={{ width: `${(bytes / total) * 100}%` }} />
          ) : null
        })}
    </div>
  )
}

function Largest({ items }: { items: LargestItem[] }) {
  const { t } = useTranslation('hosts')
  return (
    <section className="disk__largest">
      <h4 className="disk__subtitle">{t('DiskUsageCard.largestItems')}</h4>
      {items.length === 0 ? (
        <p className="disk__note">{t('DiskUsageCard.noItemsToShow')}</p>
      ) : (
        <ol className="disk__top" aria-label={t('DiskUsageCard.largestItems')}>
          {items.map((item, index) => (
            <li key={`${item.category}-${item.name}-${index}`} className="disk__item">
              <span className="disk__itemname">{item.name}</span>
              <span className="disk__kind">{LARGEST_LABEL[item.category]}</span>
              <span className="disk__num">{formatSize(item.size)}</span>
            </li>
          ))}
        </ol>
      )}
    </section>
  )
}

function Row({ categoryKey, label, noun, category }: { categoryKey: CategoryKey; label: string; noun: CountNoun; category: DiskCategory }) {
  const { t } = useTranslation('hosts')
  return (
    <li className="disk__row">
      <span className={`disk__swatch disk__swatch--${categoryKey}`} aria-hidden="true" />
      <span className="disk__name">
        {label}
        {category.count !== null && <span className="disk__count"> · {countOf(category.count, noun)}</span>}
      </span>
      <Trans t={t} i18nKey="DiskUsageCard.sizeReclaimableSize2" values={{ size: size(category.totalSize), reclaimable: size(category.reclaimableSize) }} components={{ span: <span className="disk__num" />, span2: <span className="disk__reclaim" /> }} />
    </li>
  )
}

/**
 * Disk usage of an environment as a stacked bar with a legend. Fetched on mount and on the refresh button only.
 * `onCleanUp` (ADMIN/OPERATOR) opens the prune dialog of the dashboard.
 */
export function DiskUsageCard({ hostId, onCleanUp }: { hostId: string; onCleanUp?: () => void }) {
  const { t } = useTranslation('hosts')
  const { data, error, isPending, isError, isFetching, refetch } = useDiskUsage(hostId)
  const forbidden = error instanceof ApiError && error.status === 403

  if (isPending) {
    return (
      <Card className="disk" role="status" aria-label={t('DiskUsageCard.loadingDiskUsage')} aria-busy="true">
        <Skeleton height="1.5rem" width="8rem" />
        <Skeleton height="var(--space-3)" />
        <Skeleton height="4rem" />
      </Card>
    )
  }

  const head = (
    <div className="disk__head">
      <h3 className="disk__title">{t('DiskUsageCard.diskUsage')}</h3>
      {!forbidden && (
        <IconButton icon={RefreshCw} aria-label={t('DiskUsageCard.refreshDiskUsage')} onClick={() => void refetch()} disabled={isFetching} />
      )}
    </div>
  )

  if (forbidden) {
    return (
      <Card className="disk">
        {head}
        <p className="disk__note">{t('DiskUsageCard.onlyAvailableWithAccessTo')}</p>
      </Card>
    )
  }

  if (isError || !data) {
    return (
      <Card className="disk">
        {head}
        <div className="disk__error">
          <p role="alert">{t('DiskUsageCard.couldNotLoadDiskUsage')}</p>
          <Button size="sm" onClick={() => void refetch()} loading={isFetching}>
            {t('DiskUsageCard.retry')}
          </Button>
        </div>
      </Card>
    )
  }

  const categories = CATEGORIES.map((category) => ({ ...category, value: data[category.key] }))
  const total = sumKnown(categories.map(({ value }) => value.totalSize))
  const reclaimable = sumKnown(categories.map(({ value }) => value.reclaimableSize))
  const someUnknown = categories.some(({ value }) => value.totalSize === null || value.reclaimableSize === null)

  return (
    <Card className="disk">
      {head}
      <p className="disk__summary">
        {t('DiskUsageCard.totalSizeReclaimableSize2', { size: formatSize(total), reclaimable: formatSize(reclaimable) })}
        {someUnknown && t('DiskUsageCard.someSizesAreUnknown')}
      </p>
      <Bar usage={data} total={total} />
      <ul className="disk__legend">
        {categories.map(({ key, label, noun, value }) => (
          <Row key={key} categoryKey={key} label={label} noun={noun} category={value} />
        ))}
      </ul>
      <Largest items={data.largest ?? []} />
      {onCleanUp && (
        <div className="disk__foot">
          <Button onClick={onCleanUp}>{t('DiskUsageCard.reviewCleanup')}</Button>
        </div>
      )}
    </Card>
  )
}
