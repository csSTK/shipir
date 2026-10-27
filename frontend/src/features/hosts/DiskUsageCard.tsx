import { RefreshCw } from 'lucide-react'
import { ApiError } from '../../shared/api/client.ts'
import { Button, Card, IconButton, Skeleton } from '../../shared/ui/index.ts'
import { formatSize, plural } from './format.ts'
import { useDiskUsage, type DiskCategory, type DiskUsage, type LargestCategory, type LargestItem } from './diskUsage.ts'
import './DiskUsageCard.css'

type CategoryKey = 'images' | 'containers' | 'volumes' | 'buildCache'

const CATEGORIES: { key: CategoryKey; label: string; noun: [singular: string, plural: string] }[] = [
  { key: 'images', label: 'Images', noun: ['image', 'images'] },
  { key: 'containers', label: 'Containers', noun: ['container', 'containers'] },
  { key: 'volumes', label: 'Volumes', noun: ['volume', 'volumes'] },
  { key: 'buildCache', label: 'Build cache', noun: ['entry', 'entries'] },
]

const LARGEST_LABEL: Record<LargestCategory, string> = {
  IMAGE: 'Image',
  CONTAINER: 'Container',
  VOLUME: 'Volume',
  BUILD_CACHE: 'Build cache',
}

const size = (bytes: number | null) => (bytes === null ? 'unknown' : formatSize(bytes))

/** Sum of the reported values; unreported ones count as zero. */
const sumKnown = (values: (number | null)[]) => values.reduce<number>((total, value) => total + (value ?? 0), 0)

/** Accessible summary of the bar, which is a picture for sighted users only. */
function summarise(usage: DiskUsage): string {
  const parts = CATEGORIES.map(({ key, label }) => `${label} ${size(usage[key].totalSize)}`)
  return `Disk usage: ${parts.join(', ')}.`
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
  return (
    <section className="disk__largest">
      <h4 className="disk__subtitle">Largest items</h4>
      {items.length === 0 ? (
        <p className="disk__note">No items to show.</p>
      ) : (
        <ol className="disk__top" aria-label="Largest items">
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

function Row({ categoryKey, label, noun, category }: { categoryKey: CategoryKey; label: string; noun: [string, string]; category: DiskCategory }) {
  return (
    <li className="disk__row">
      <span className={`disk__swatch disk__swatch--${categoryKey}`} aria-hidden="true" />
      <span className="disk__name">
        {label}
        {category.count !== null && <span className="disk__count"> · {plural(category.count, ...noun)}</span>}
      </span>
      <span className="disk__num">{size(category.totalSize)}</span>
      <span className="disk__reclaim">Reclaimable {size(category.reclaimableSize)}</span>
    </li>
  )
}

/**
 * Disk usage of an environment as a stacked bar with a legend. Fetched on mount and on the refresh button only.
 * `onCleanUp` (ADMIN/OPERATOR) opens the prune dialog of the dashboard.
 */
export function DiskUsageCard({ hostId, onCleanUp }: { hostId: string; onCleanUp?: () => void }) {
  const { data, error, isPending, isError, isFetching, refetch } = useDiskUsage(hostId)
  const forbidden = error instanceof ApiError && error.status === 403

  if (isPending) {
    return (
      <Card className="disk" role="status" aria-label="Loading disk usage" aria-busy="true">
        <Skeleton height="1.5rem" width="8rem" />
        <Skeleton height="var(--space-3)" />
        <Skeleton height="4rem" />
      </Card>
    )
  }

  const head = (
    <div className="disk__head">
      <h3 className="disk__title">Disk usage</h3>
      {!forbidden && (
        <IconButton icon={RefreshCw} aria-label="Refresh disk usage" onClick={() => void refetch()} disabled={isFetching} />
      )}
    </div>
  )

  if (forbidden) {
    return (
      <Card className="disk">
        {head}
        <p className="disk__note">Only available with access to the whole environment.</p>
      </Card>
    )
  }

  if (isError || !data) {
    return (
      <Card className="disk">
        {head}
        <div className="disk__error">
          <p role="alert">Could not load disk usage.</p>
          <Button size="sm" onClick={() => void refetch()} loading={isFetching}>
            Retry
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
        Total {formatSize(total)} · Reclaimable {formatSize(reclaimable)}
        {someUnknown && '. Some sizes are unknown.'}
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
          <Button onClick={onCleanUp}>Review cleanup</Button>
        </div>
      )}
    </Card>
  )
}
