import { useSearchParams, useParams } from 'react-router-dom'
import { describeResourceError } from '../hosts/format.ts'
import { Badge, ResourceTable, type ResourceColumn } from '../../shared/ui/index.ts'
import { useContainers, type Container } from './api.ts'
import './ContainersPage.css'

const dateFormat = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' })

function stateTone(state: string): 'ok' | 'warn' | 'bad' | 'muted' {
  if (state === 'running') return 'ok'
  if (state === 'restarting' || state === 'paused') return 'warn'
  if (state === 'dead') return 'bad'
  return 'muted'
}

function healthTone(health: string): 'ok' | 'warn' | 'bad' {
  return health === 'healthy' ? 'ok' : health === 'unhealthy' ? 'bad' : 'warn'
}

const columns: ResourceColumn<Container>[] = [
  { id: 'name', header: 'Name', sortValue: (c) => c.name, render: (c) => <strong>{c.name}</strong> },
  {
    id: 'state',
    header: 'State',
    sortValue: (c) => c.state,
    render: (c) => <Badge tone={stateTone(c.state)}>{c.state}</Badge>,
  },
  {
    id: 'health',
    header: 'Health',
    sortValue: (c) => c.health,
    render: (c) => (c.health ? <Badge tone={healthTone(c.health)}>{c.health}</Badge> : '–'),
  },
  { id: 'image', header: 'Image', sortValue: (c) => c.image, render: (c) => c.image },
  { id: 'deployment', header: 'Deployment', sortValue: (c) => c.composeProject, render: (c) => c.composeProject ?? '–' },
  {
    id: 'ports',
    header: 'Ports',
    render: (c) =>
      c.ports.length ? (
        <ul className="containers__ports">
          {c.ports.map((p) => (
            <li key={p}>{p}</li>
          ))}
        </ul>
      ) : (
        '–'
      ),
  },
  {
    id: 'created',
    header: 'Created',
    sortValue: (c) => Date.parse(c.created),
    render: (c) => dateFormat.format(new Date(c.created)),
  },
]

/** `/environments/:id/containers`: all containers of one environment, optionally `?deployment=<project>`. */
export function ContainersPage() {
  const { id = '' } = useParams()
  const [params, setParams] = useSearchParams()
  const deployment = params.get('deployment')
  const { data, isPending, error, refetch } = useContainers(id)

  const rows = deployment ? data?.filter((c) => c.composeProject === deployment) : data

  return (
    <section className="containers">
      <h2 className="containers__title">Containers</h2>
      <ResourceTable
        label="containers"
        columns={columns}
        rows={rows}
        rowKey={(c) => c.id}
        searchText={(c) => `${c.name} ${c.image} ${c.composeProject ?? ''}`}
        defaultSort="name"
        emptyMessage={deployment ? `No containers in deployment ${deployment}.` : 'No containers in this environment.'}
        isPending={isPending}
        error={error ? describeResourceError(error) : null}
        onRetry={() => void refetch()}
        toolbar={
          deployment && (
            <button type="button" className="containers__chip" onClick={() => setParams({})}>
              Deployment: {deployment} <span aria-hidden="true">×</span>
            </button>
          )
        }
      />
    </section>
  )
}
