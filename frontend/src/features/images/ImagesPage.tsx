import { Tag, Upload } from 'lucide-react'
import { useState } from 'react'
import { useCurrentUser } from '../auth/index.ts'
import { deleteMenu } from '../hosts/deleteRowActions.tsx'
import { DeleteResourceDialog } from '../hosts/DeleteResourceDialog.tsx'
import { useParams } from 'react-router-dom'
import { describeResourceError, formatSize } from '../hosts/format.ts'
import { Badge, Button, PageHeader, ResourceTable, type MenuEntry, type ResourceColumn } from '../../shared/ui/index.ts'
import { useDeleteImage, useImages, type Image } from './api.ts'
import { BuildImageDialog } from './BuildImageDialog.tsx'
import { PullImageDialog } from './PullImageDialog.tsx'
import { PushImageDialog } from './PushImageDialog.tsx'
import { TagImageDialog } from './TagImageDialog.tsx'
import { useTranslation } from 'react-i18next'
import i18n from 'i18next'
import { dateTimeFormat } from '../../shared/i18n/format.ts'
import './ImagesPage.css'

const dateFormat = dateTimeFormat({
  dateStyle: 'medium',
  timeStyle: 'short',
})

/** `sha256:abcdef...` -> first 12 hex characters, like `docker images`. */
function shortId(id: string): string {
  return id.replace(/^sha256:/, '').slice(0, 12)
}

const columns: ResourceColumn<Image>[] = [
  {
    id: 'tags',
    get header() { return i18n.t('images:ImagesPage.tags') },
    sortValue: (i) => i.repoTags[0] ?? null,
    render: (i) =>
      i.repoTags.length ? (
        <ul className="images__tags">
          {i.repoTags.map((t, n) => (
            <li key={t}>{n === 0 ? <strong>{t}</strong> : t}</li>
          ))}
        </ul>
      ) : (
        <span>{i18n.t('images:ImagesPage.text')}</span>
      ),
  },
  {
    id: 'id',
    header: 'ID',
    sortValue: (i) => shortId(i.id),
    render: (i) => <span className="images__id">{shortId(i.id)}</span>,
  },
  {
    id: 'size',
    get header() { return i18n.t('images:ImagesPage.size') },
    sortValue: (i) => i.size,
    render: (i) => (i.size === null ? '–' : formatSize(i.size)),
  },
  {
    id: 'created',
    get header() { return i18n.t('images:ImagesPage.created') },
    sortValue: (i) => Date.parse(i.created),
    render: (i) => dateFormat.format(new Date(i.created)),
  },
  {
    id: 'usage',
    get header() { return i18n.t('images:ImagesPage.usage') },
    sortValue: (i) => (i.inUse ? 1 : 0),
    render: (i) => <Badge tone={i.inUse ? 'ok' : 'muted'}>{i.inUse ? i18n.t('images:ImagesPage.inUse') : i18n.t('images:ImagesPage.unused')}</Badge>,
  },
]

/** 'Update available' badge; writers get it as a button that opens the pull dialog for the image's first tag. */
function updateColumn(onPull: ((i: Image) => void) | null): ResourceColumn<Image> {
  return {
    id: 'update',
    header: i18n.t('images:ImagesPage.update'),
    sortValue: (i) => (i.updateAvailable ? 1 : 0),
    render: (i) => {
      if (!i.updateAvailable) return '–'
      if (!onPull || !i.repoTags.length) return <Badge tone="warn">{i18n.t('images:ImagesPage.updateAvailable')}</Badge>
      return (
        <Button variant="ghost" className="images__update" aria-label={i18n.t('images:ImagesPage.pullUpdateValue', { value: i.repoTags[0] })} onClick={() => onPull(i)}>
          <Badge tone="warn">{i18n.t('images:ImagesPage.updateAvailable')}</Badge>
          <span>{i18n.t('images:ImagesPage.pullUpdate')}</span>
        </Button>
      )
    },
  }
}

/** `/environments/:id/images`: all images of one environment. */
export function ImagesPage() {
  const { t: tr } = useTranslation('images')
  const { id = '' } = useParams()
  const { data: user } = useCurrentUser()
  const isAdmin = user?.role === 'ADMIN'
  const canWrite = isAdmin || user?.role === 'OPERATOR'
  const { data, isPending, error, refetch } = useImages(id)
  const del = useDeleteImage(id)
  const [target, setTarget] = useState<Image | null>(null)
  const [tagging, setTagging] = useState<Image | null>(null)
  const [pushing, setPushing] = useState<Image | null>(null)
  const [building, setBuilding] = useState(false)
  const [pulling, setPulling] = useState<{ reference: string } | null>(null)
  const withUpdate = [...columns, updateColumn(canWrite ? (i) => setPulling({ reference: i.repoTags[0] }) : null)]

  return (
    <section className="ui-page images">
      <PageHeader
        title={tr('ImagesPage.images')}
        actions={
          canWrite && (
            <>
              {isAdmin && <Button onClick={() => setBuilding(true)}>{tr('ImagesPage.buildImage')}</Button>}
              <Button variant="primary" onClick={() => setPulling({ reference: '' })}>
                {tr('ImagesPage.pullImage')}
              </Button>
            </>
          )
        }
      />
      <ResourceTable
        label={tr('ImagesPage.images2')}
        columns={withUpdate}
        rows={data}
        rowKey={(i) => i.id}
        searchText={(i) => `${i.repoTags.join(' ')} ${shortId(i.id)}`}
        defaultSort="tags"
        emptyMessage={tr('ImagesPage.noImagesInThisEnvironment')}
        isPending={isPending}
        error={error ? describeResourceError(error) : null}
        onRetry={() => void refetch()}
        rowLabel={(i) => i.repoTags[0] ?? shortId(i.id)}
        rowActions={
          canWrite
            ? (i) => ({
                menu: [
                  {
                    label: tr('ImagesPage.tag'),
                    icon: <Tag size={16} strokeWidth={1.75} />,
                    onSelect: () => setTagging(i),
                  },
                  {
                    label: tr('ImagesPage.push'),
                    icon: <Upload size={16} strokeWidth={1.75} />,
                    disabled: !i.repoTags.length,
                    onSelect: () => setPushing(i),
                  },
                  { type: 'separator' },
                  ...deleteMenu(() => setTarget(i)),
                ] satisfies MenuEntry[],
              })
            : undefined
        }
      />
      {pulling && <PullImageDialog hostId={id} initialReference={pulling.reference} onClose={() => setPulling(null)} />}
      {building && <BuildImageDialog hostId={id} onClose={() => setBuilding(false)} />}
      {tagging && <TagImageDialog hostId={id} image={tagging} onClose={() => setTagging(null)} />}
      {pushing && <PushImageDialog hostId={id} image={pushing} onClose={() => setPushing(null)} />}
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
