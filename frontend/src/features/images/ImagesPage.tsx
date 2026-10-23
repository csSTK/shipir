import { useParams } from 'react-router-dom'
import { describeResourceError, formatSize } from '../hosts/format.ts'
import { Badge, ResourceTable, type ResourceColumn } from '../../shared/ui/index.ts'
import { useImages, type Image } from './api.ts'
import './ImagesPage.css'

const dateFormat = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' })

/** `sha256:abcdef...` -> first 12 hex characters, like `docker images`. */
function shortId(id: string): string {
  return id.replace(/^sha256:/, '').slice(0, 12)
}

const columns: ResourceColumn<Image>[] = [
  {
    id: 'tags',
    header: 'Tags',
    sortValue: (i) => i.repoTags[0] ?? null,
    render: (i) =>
      i.repoTags.length ? (
        <ul className="images__tags">
          {i.repoTags.map((t, n) => (
            <li key={t}>{n === 0 ? <strong>{t}</strong> : t}</li>
          ))}
        </ul>
      ) : (
        <span>&lt;none&gt;</span>
      ),
  },
  { id: 'id', header: 'ID', sortValue: (i) => shortId(i.id), render: (i) => <span className="images__id">{shortId(i.id)}</span> },
  { id: 'size', header: 'Size', sortValue: (i) => i.size, render: (i) => (i.size === null ? '–' : formatSize(i.size)) },
  { id: 'created', header: 'Created', sortValue: (i) => Date.parse(i.created), render: (i) => dateFormat.format(new Date(i.created)) },
  {
    id: 'usage',
    header: 'Usage',
    sortValue: (i) => (i.inUse ? 1 : 0),
    render: (i) => <Badge tone={i.inUse ? 'ok' : 'muted'}>{i.inUse ? 'In use' : 'Unused'}</Badge>,
  },
]

/** `/environments/:id/images`: all images of one environment. */
export function ImagesPage() {
  const { id = '' } = useParams()
  const { data, isPending, error, refetch } = useImages(id)

  return (
    <section className="images">
      <h2 className="images__title">Images</h2>
      <ResourceTable
        label="images"
        columns={columns}
        rows={data}
        rowKey={(i) => i.id}
        searchText={(i) => `${i.repoTags.join(' ')} ${shortId(i.id)}`}
        defaultSort="tags"
        emptyMessage="No images in this environment."
        isPending={isPending}
        error={error ? describeResourceError(error) : null}
        onRetry={() => void refetch()}
      />
    </section>
  )
}
