import { useState, type FormEvent } from 'react'
import { Button, Dialog, Select, TextField } from '../../shared/ui/index.ts'
import { describeResourcesError, useUpdateContainerResources, type ResourcesSpec, type RestartPolicy } from './api.ts'
import type { ContainerDetails } from './details/api.ts'
import { useTranslation } from 'react-i18next'
import i18n from 'i18next'

const MIB = 1024 * 1024
const MIN_MEMORY_MIB = 6
const POLICIES: RestartPolicy[] = ['no', 'always', 'unless-stopped', 'on-failure']

interface ResourcesForm {
  memory: string
  cpus: string
  policy: RestartPolicy
}

/** Field problems and the request body; a field equal to its prefilled value or left blank stays unchanged, 0 removes the limit. */
function build(form: ResourcesForm, original: ResourcesForm): { spec: ResourcesSpec; problems: Record<string, string> } {
  const problems: Record<string, string> = {}
  const spec: ResourcesSpec = { memoryBytes: null, cpus: null, restartPolicy: form.policy === original.policy ? null : form.policy }
  const memory = form.memory.trim()
  if (memory && memory !== original.memory) {
    const mib = Number(memory)
    if (!Number.isFinite(mib) || (mib !== 0 && mib < MIN_MEMORY_MIB)) problems.memory = i18n.t('containers:ContainerResourcesDialog.use0UnlimitedOrAt', { MIN_MEMORY_MIB })
    else spec.memoryBytes = Math.round(mib * MIB)
  }
  const cpus = form.cpus.trim()
  if (cpus && cpus !== original.cpus) {
    const n = Number(cpus)
    if (!Number.isFinite(n) || (n !== 0 && (n < 0.01 || n > 1024))) problems.cpus = i18n.t('containers:ContainerResourcesDialog.use0UnlimitedOr0')
    else spec.cpus = n
  }
  if (Object.keys(problems).length === 0 && spec.memoryBytes === null && spec.cpus === null && spec.restartPolicy === null) {
    problems.form = i18n.t('containers:ContainerResourcesDialog.changeAtLeastOneValue')
  }
  return { spec, problems }
}

function currentPolicy(c: ContainerDetails): RestartPolicy {
  const n = c.restartPolicy.name
  return n === 'always' || n === 'unless-stopped' || n === 'on-failure' ? n : 'no'
}

/** The limits as the form shows them: MiB and fractional CPUs, empty when unset. */
function currentForm(c: ContainerDetails): ResourcesForm {
  return {
    memory: c.memoryBytes == null ? '' : String(c.memoryBytes / MIB),
    cpus: c.cpus == null ? '' : String(c.cpus),
    policy: currentPolicy(c),
  }
}

/** Changes memory, CPU and restart policy of a container in place (no restart). Fields start with the current limits; unchanged or empty ones are not sent. */
export function ContainerResourcesDialog({ hostId, container, onClose }: { hostId: string; container: ContainerDetails; onClose: () => void }) {
  const { t } = useTranslation('containers')
  const [original] = useState(() => currentForm(container))
  const [memory, setMemory] = useState(original.memory)
  const [cpus, setCpus] = useState(original.cpus)
  const [policy, setPolicy] = useState<RestartPolicy>(original.policy)
  const [touched, setTouched] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const update = useUpdateContainerResources(hostId, container.id)
  const { spec, problems } = build({ memory, cpus, policy }, original)

  function submit(e: FormEvent) {
    e.preventDefault()
    setTouched(true)
    if (Object.keys(problems).length > 0) return
    setError(null)
    update.mutate(spec, { onSuccess: onClose, onError: (err) => setError(describeResourcesError(err)) })
  }

  return (
    <Dialog size="lg" open onOpenChange={(open) => !open && onClose()} title={t('ContainerResourcesDialog.resourceLimits')} description={t('ContainerResourcesDialog.appliedToNameWithoutRestarting', { name: container.name })}>
      <form className="ui-form" onSubmit={submit} noValidate>
        <div className="ui-form-row">
          <TextField
            label={t('ContainerResourcesDialog.memoryLimitMib')}
            value={memory}
            onChange={(e) => setMemory(e.target.value)}
            inputMode="decimal"
            placeholder={t('ContainerResourcesDialog.unchanged')}
            error={touched ? problems.memory : null}
            autoComplete="off"
            autoFocus
          />
          <TextField
            label={t('ContainerResourcesDialog.cpus')}
            value={cpus}
            onChange={(e) => setCpus(e.target.value)}
            inputMode="decimal"
            placeholder={t('ContainerResourcesDialog.unchanged')}
            error={touched ? problems.cpus : null}
            autoComplete="off"
          />
        </div>
        <p className="ui-hint">{t('ContainerResourcesDialog.leaveAFieldEmptyTo')}</p>
        <Select label={t('ContainerResourcesDialog.restartPolicy')} value={policy} onChange={(e) => setPolicy(e.target.value as RestartPolicy)}>
          {POLICIES.map((p) => (
            <option key={p} value={p}>
              {p}
            </option>
          ))}
        </Select>
        {touched && problems.form && (
          <p role="alert" className="ui-error">
            {problems.form}
          </p>
        )}
        {error && (
          <p role="alert" className="ui-error">
            {error}
          </p>
        )}
        <div className="ui-dialog-actions">
          <Button variant="ghost" onClick={onClose}>
            {t('ContainerResourcesDialog.cancel')}
          </Button>
          <Button variant="primary" type="submit" loading={update.isPending}>
            {t('ContainerResourcesDialog.applyLimits')}
          </Button>
        </div>
      </form>
    </Dialog>
  )
}
