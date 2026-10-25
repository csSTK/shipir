import { useState, type FormEvent } from 'react'
import { Button, Dialog } from '../../shared/ui/index.ts'
import { describeResourcesError, useUpdateContainerResources, type ResourcesSpec, type RestartPolicy } from './api.ts'
import type { ContainerDetails } from './details/api.ts'

const MIB = 1024 * 1024
const MIN_MEMORY_MIB = 6
const POLICIES: RestartPolicy[] = ['no', 'always', 'unless-stopped', 'on-failure']

/** Field problems and the request body; blank memory/CPUs stay unchanged, 0 removes the limit. */
function build(memory: string, cpus: string, policy: RestartPolicy, current: RestartPolicy): { spec: ResourcesSpec; problems: Record<string, string> } {
  const problems: Record<string, string> = {}
  const spec: ResourcesSpec = { memoryBytes: null, cpus: null, restartPolicy: policy === current ? null : policy }
  if (memory.trim()) {
    const mib = Number(memory)
    if (!Number.isFinite(mib) || (mib !== 0 && mib < MIN_MEMORY_MIB)) problems.memory = `Use 0 (unlimited) or at least ${MIN_MEMORY_MIB} MiB.`
    else spec.memoryBytes = Math.round(mib * MIB)
  }
  if (cpus.trim()) {
    const n = Number(cpus)
    if (!Number.isFinite(n) || (n !== 0 && (n < 0.01 || n > 1024))) problems.cpus = 'Use 0 (unlimited) or 0.01 to 1024.'
    else spec.cpus = n
  }
  if (Object.keys(problems).length === 0 && spec.memoryBytes === null && spec.cpus === null && spec.restartPolicy === null) {
    problems.form = 'Change at least one value.'
  }
  return { spec, problems }
}

function currentPolicy(c: ContainerDetails): RestartPolicy {
  const n = c.restartPolicy.name
  return n === 'always' || n === 'unless-stopped' || n === 'on-failure' ? n : 'no'
}

/** Changes memory, CPU and restart policy of a container in place (no restart). The current limits are not part of inspect, so empty fields stay unchanged. */
export function ContainerResourcesDialog({ hostId, container, onClose }: { hostId: string; container: ContainerDetails; onClose: () => void }) {
  const current = currentPolicy(container)
  const [memory, setMemory] = useState('')
  const [cpus, setCpus] = useState('')
  const [policy, setPolicy] = useState<RestartPolicy>(current)
  const [touched, setTouched] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const update = useUpdateContainerResources(hostId, container.id)
  const { spec, problems } = build(memory, cpus, policy, current)

  function submit(e: FormEvent) {
    e.preventDefault()
    setTouched(true)
    if (Object.keys(problems).length > 0) return
    setError(null)
    update.mutate(spec, { onSuccess: onClose, onError: (err) => setError(describeResourcesError(err)) })
  }

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()} title="Resource limits" description={`Applied to ${container.name} without restarting it.`}>
      <form className="ui-form" onSubmit={submit} noValidate>
        <div className="ui-form-row">
          <label className="ui-form-field">
            Memory limit (MiB)
            <input
              value={memory}
              onChange={(e) => setMemory(e.target.value)}
              inputMode="decimal"
              placeholder="Unchanged"
              aria-invalid={touched && problems.memory ? true : undefined}
              autoComplete="off"
              autoFocus
            />
          </label>
          <label className="ui-form-field">
            CPUs
            <input
              value={cpus}
              onChange={(e) => setCpus(e.target.value)}
              inputMode="decimal"
              placeholder="Unchanged"
              aria-invalid={touched && problems.cpus ? true : undefined}
              autoComplete="off"
            />
          </label>
        </div>
        <p className="ui-hint">Leave a field empty to keep its current limit, enter 0 to remove it.</p>
        <label className="ui-form-field">
          Restart policy
          <select value={policy} onChange={(e) => setPolicy(e.target.value as RestartPolicy)}>
            {POLICIES.map((p) => (
              <option key={p} value={p}>
                {p}
              </option>
            ))}
          </select>
        </label>
        {touched && Object.values(problems).map((p) => (
          <p key={p} role="alert" className="ui-error">
            {p}
          </p>
        ))}
        {error && (
          <p role="alert" className="ui-error">
            {error}
          </p>
        )}
        <div className="ui-dialog-actions">
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button variant="primary" type="submit" loading={update.isPending}>
            Apply limits
          </Button>
        </div>
      </form>
    </Dialog>
  )
}
