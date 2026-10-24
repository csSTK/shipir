import { useState } from 'react'
import { useCurrentUser } from '../auth/index.ts'
import { deleteColumn } from '../hosts/deleteColumn.tsx'
import { DeleteResourceDialog } from '../hosts/DeleteResourceDialog.tsx'
import { useParams } from 'react-router-dom'
import { describeResourceError, formatSize } from '../hosts/format.ts'
import { Badge, ResourceTable, type ResourceColumn } from '../../shared/ui/index.ts'
import { useDeleteImage, useImages, type Image } from './api.ts'
import { PullImageDialog } from './PullImageDialog.tsx'
import './ImagesPage.css'
import { Button } from '../../shared/ui/index.ts'

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

/** 'Update available' badge; writers get it as a button that opens the pull dialog for the image's first tag. */
function updateColumn(onPull: ((i: Image) => void) | null): ResourceColumn<Image> {
  return {
    id: 'update',
    header: 'Update',
    sortValue: (i) => (i.updateAvailable ? 1 : 0),
    render: (i) => {
      if (!i.updateAvailable) return '–'
      if (!onPull || !i.repoTags.length) return <Badge tone="warn">Update available</Badge>
      return (
        <Button variant="ghost" className="images__update" aria-label={`Pull & update ${i.repoTags[0]}`} onClick={() => onPull(i)}>
          <Badge tone="warn">Update available</Badge>
          <span>Pull &amp; update</span>
        </Button>
      )
    },
  }
}

/** `/environments/:id/images`: all images of one environment. */
export function ImagesPage() {
  const { id = '' } = useParams()
  const { data: user } = useCurrentUser()
  const canWrite = user?.role === 'ADMIN' || user?.role === 'OPERATOR'
  const { data, isPending, error, refetch } = useImages(id)
  const del = useDeleteImage(id)
  const [target, setTarget] = useState<Image | null>(null)
  const [pulling, setPulling] = useState<{ reference: string } | null>(null)
  const withUpdate = [...columns, updateColumn(canWrite ? (i) => setPulling({ reference: i.repoTags[0] }) : null)]
  const tableColumns = canWrite ? [...withUpdate, deleteColumn<Image>('image', (i) => i.repoTags[0] ?? shortId(i.id), setTarget)] : withUpdate

  return (
    <section className="images">
      <div className="images__bar">
        <h2 className="images__title">Images</h2>
        {canWrite && (
          <Button variant="primary" onClick={() => setPulling({ reference: '' })}>
            Pull image
          </Button>
        )}
      </div>
      <ResourceTable
        label="images"
        columns={tableColumns}
        rows={data}
        rowKey={(i) => i.id}
        searchText={(i) => `${i.repoTags.join(' ')} ${shortId(i.id)}`}
        defaultSort="tags"
        emptyMessage="No images in this environment."
        isPending={isPending}
        error={error ? describeResourceError(error) : null}
        onRetry={() => void refetch()}
      />
      {pulling && <PullImageDialog hostId={id} initialReference={pulling.reference} onClose={() => setPulling(null)} />}
      {target && (
        <DeleteResourceDialog
          kind="image"
          name={target.repoTags[0] ?? shortId(target.id)}
          onDelete={() => del.mutateAsync(target.id)}
          onClose={() => setTarget(null)}
        />
      )}
    </section>
  )
}
