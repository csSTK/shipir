import { useParams } from 'react-router-dom'
import { describeResourceError } from '../hosts/format.ts'
import { Badge, ResourceTable, type ResourceColumn } from '../../shared/ui/index.ts'
import { useVolumes, type Volume } from './api.ts'
import './VolumesPage.css'

const dateFormat = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' })

const columns: ResourceColumn<Volume>[] = [
  { id: 'name', header: 'Name', sortValue: (v) => v.name, render: (v) => <strong>{v.name}</strong> },
  { id: 'driver', header: 'Driver', sortValue: (v) => v.driver, render: (v) => v.driver },
  {
    id: 'created',
    header: 'Created',
    sortValue: (v) => (v.created ? Date.parse(v.created) : null),
    render: (v) => (v.created ? dateFormat.format(new Date(v.created)) : '–'),
  },
  {
    id: 'usage',
    header: 'Usage',
    sortValue: (v) => (v.inUse ? 1 : 0),
    render: (v) => <Badge tone={v.inUse ? 'ok' : 'muted'}>{v.inUse ? 'In use' : 'Unused'}</Badge>,
  },
]

/** `/environments/:id/volumes`: all volumes of one environment. */
export function VolumesPage() {
  const { id = '' } = useParams()
  const { data, isPending, error, refetch } = useVolumes(id)

  return (
    <section className="volumes">
      <h2 className="volumes__title">Volumes</h2>
      <ResourceTable
        label="volumes"
        columns={columns}
        rows={data}
        rowKey={(v) => v.name}
        searchText={(v) => `${v.name} ${v.driver}`}
        defaultSort="name"
        emptyMessage="No volumes in this environment."
        isPending={isPending}
        error={error ? describeResourceError(error) : null}
        onRetry={() => void refetch()}
      />
    </section>
  )
}
