import { useState } from 'react'
import { useCurrentUser } from '../auth/index.ts'
import { deleteMenu } from '../hosts/deleteRowActions.tsx'
import { DeleteResourceDialog } from '../hosts/DeleteResourceDialog.tsx'
import { useParams } from 'react-router-dom'
import { describeResourceError } from '../hosts/format.ts'
import { Badge, Button, PageHeader, ResourceTable, type ResourceColumn } from '../../shared/ui/index.ts'
import { useDeleteVolume, useVolumes, type Volume } from './api.ts'
import { CreateVolumeDialog } from './CreateVolumeDialog.tsx'
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
  const { data: user } = useCurrentUser()
  const canWrite = user?.role === 'ADMIN' || user?.role === 'OPERATOR'
  const { data, isPending, error, refetch } = useVolumes(id)
  const del = useDeleteVolume(id)
  const [target, setTarget] = useState<Volume | null>(null)
  const [creating, setCreating] = useState(false)

  return (
    <section className="volumes">
      <PageHeader
        title="Volumes"
        actions={
          canWrite && (
            <Button variant="primary" onClick={() => setCreating(true)}>
              Create volume
            </Button>
          )
        }
      />
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
        rowLabel={(v) => v.name}
        rowActions={canWrite ? (v) => ({ menu: deleteMenu(() => setTarget(v)) }) : undefined}
      />
      {creating && <CreateVolumeDialog hostId={id} onClose={() => setCreating(false)} />}
      {target && (
        <DeleteResourceDialog kind="volume" name={target.name} onDelete={() => del.mutateAsync(target.name)} onClose={() => setTarget(null)} />
      )}
    </section>
  )
}
