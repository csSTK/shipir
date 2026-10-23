import { useParams } from 'react-router-dom'
import { describeResourceError } from '../hosts/format.ts'
import { Badge, ResourceTable, type ResourceColumn } from '../../shared/ui/index.ts'
import { useNetworks, type Network } from './api.ts'
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
  const { data, isPending, error, refetch } = useNetworks(id)

  return (
    <section className="networks">
      <h2 className="networks__title">Networks</h2>
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
      />
    </section>
  )
}
