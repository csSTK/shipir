import { useState } from 'react'
import { useCurrentUser } from '../auth/index.ts'
import { deleteMenu } from '../hosts/deleteRowActions.tsx'
import { DeleteResourceDialog } from '../hosts/DeleteResourceDialog.tsx'
import { useParams } from 'react-router-dom'
import { describeResourceError } from '../hosts/format.ts'
import { Badge, Button, PageHeader, ResourceTable, type ResourceColumn } from '../../shared/ui/index.ts'
import { useDeleteNetwork, useNetworks, type Network } from './api.ts'
import { CreateNetworkDialog } from './CreateNetworkDialog.tsx'
import './NetworksPage.css'

const columns: ResourceColumn<Network>[] = [
  {
    id: 'name',
    header: 'Name',
    sortValue: (n) => n.name,
    render: (n) => (
      <>
        <strong>{n.name}</strong>
        {n.internal && (
          <>
            {' '}
            <Badge tone="muted">Internal</Badge>
          </>
        )}
      </>
    ),
  },
  { id: 'driver', header: 'Driver', sortValue: (n) => n.driver, render: (n) => n.driver },
  { id: 'scope', header: 'Scope', sortValue: (n) => n.scope, render: (n) => n.scope },
  { id: 'subnet', header: 'Subnet', sortValue: (n) => n.subnet, render: (n) => n.subnet ?? '–' },
  { id: 'containers', header: 'Containers', sortValue: (n) => n.containerCount, render: (n) => n.containerCount },
]

/** `/environments/:id/networks`: all networks of one environment. */
export function NetworksPage() {
  const { id = '' } = useParams()
  const { data: user } = useCurrentUser()
  const canWrite = user?.role === 'ADMIN' || user?.role === 'OPERATOR'
  const { data, isPending, error, refetch } = useNetworks(id)
  const del = useDeleteNetwork(id)
  const [target, setTarget] = useState<Network | null>(null)
  const [creating, setCreating] = useState(false)

  return (
    <section className="networks">
      <PageHeader
        title="Networks"
        actions={
          canWrite && (
            <Button variant="primary" onClick={() => setCreating(true)}>
              Create network
            </Button>
          )
        }
      />
      <ResourceTable
        label="networks"
        columns={columns}
        rows={data}
        rowKey={(n) => n.id}
        searchText={(n) => `${n.name} ${n.driver} ${n.scope} ${n.subnet ?? ''}`}
        defaultSort="name"
        emptyMessage="No networks in this environment."
        isPending={isPending}
        error={error ? describeResourceError(error) : null}
        onRetry={() => void refetch()}
        rowLabel={(n) => n.name}
        rowActions={canWrite ? (n) => ({ menu: deleteMenu(() => setTarget(n)) }) : undefined}
      />
      {creating && <CreateNetworkDialog hostId={id} isAdmin={user?.role === 'ADMIN'} onClose={() => setCreating(false)} />}
      {target && (
        <DeleteResourceDialog kind="network" name={target.name} onDelete={() => del.mutateAsync(target.id)} onClose={() => setTarget(null)} />
      )}
    </section>
  )
}
