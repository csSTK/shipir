import { Link, useParams } from 'react-router-dom'
import { describeResourceError } from '../hosts/format.ts'
import { Badge, ResourceTable, type ResourceColumn } from '../../shared/ui/index.ts'
import { useStacks, type StackStatus, type StackSummary } from './api.ts'
import './StacksPage.css'

const STATUS: Record<StackStatus, { label: string; tone: 'ok' | 'warn' | 'muted' }> = {
  RUNNING: { label: 'Running', tone: 'ok' },
  PARTIAL: { label: 'Partial', tone: 'warn' },
  STOPPED: { label: 'Stopped', tone: 'muted' },
}

/** `/environments/:id/stacks`: Compose projects of one environment. */
export function StacksPage() {
  const { id = '' } = useParams()
  const { data, isPending, error, refetch } = useStacks(id)

  const columns: ResourceColumn<StackSummary>[] = [
    {
      id: 'name',
      header: 'Name',
      sortValue: (s) => s.name,
      render: (s) => (
        <Link className="stacks__link" to={`/environments/${id}/containers?stack=${encodeURIComponent(s.name)}`}>
          {s.name}
        </Link>
      ),
    },
    {
      id: 'status',
      header: 'Status',
      sortValue: (s) => s.status,
      render: (s) => <Badge tone={STATUS[s.status].tone}>{STATUS[s.status].label}</Badge>,
    },
    {
      id: 'running',
      header: 'Running',
      sortValue: (s) => s.runningCount,
      render: (s) => `${s.runningCount} / ${s.containerCount}`,
    },
    { id: 'dir', header: 'Working directory', render: (s) => s.workingDir ?? '–' },
  ]

  return (
    <section className="stacks">
      <h2 className="stacks__title">Stacks</h2>
      <ResourceTable
        label="stacks"
        columns={columns}
        rows={data}
        rowKey={(s) => s.name}
        searchText={(s) => `${s.name} ${s.workingDir ?? ''}`}
        defaultSort="name"
        emptyMessage="No stacks in this environment."
        isPending={isPending}
        error={error ? describeResourceError(error) : null}
        onRetry={() => void refetch()}
      />
    </section>
  )
}
