import { useId, useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { useCreateStack, type EnvEntry } from './api.ts'
import { describeStackError } from './stackErrors.ts'
import { StackDialog } from './StackDialog.tsx'

const NAME_PATTERN = /^[a-z0-9][a-z0-9_-]*$/

/**
 * Create form. Plain textarea and simple env rows for now; shipr-e42.14 swaps in the
 * Monaco and env editors.
 */
export function AddStackDialog({ hostId, onClose }: { hostId: string; onClose: () => void }) {
  const titleId = useId()
  const navigate = useNavigate()
  const create = useCreateStack(hostId)
  const [name, setName] = useState('')
  const [yaml, setYaml] = useState('')
  const [env, setEnv] = useState<EnvEntry[]>([])
  const [problem, setProblem] = useState<string | null>(null)

  function setRow(i: number, patch: Partial<EnvEntry>) {
    setEnv((rows) => rows.map((r, j) => (j === i ? { ...r, ...patch } : r)))
  }

  function submit(e: FormEvent) {
    e.preventDefault()
    const trimmed = name.trim()
    if (!NAME_PATTERN.test(trimmed) || trimmed.length > 63) {
      setProblem('Use lowercase letters, digits, hyphens and underscores, up to 63 characters.')
      return
    }
    if (yaml.trim() === '') {
      setProblem('Enter the Compose file.')
      return
    }
    setProblem(null)
    const entries = env.filter((r) => r.key.trim() !== '').map((r) => ({ key: r.key.trim(), value: r.value }))
    create.mutate(
      { name: trimmed, yaml, env: entries },
      {
        onSuccess: (stack) => navigate(`/environments/${hostId}/stacks/${stack.id}`),
        onError: (err) => setProblem(describeStackError(err, 'Could not create the stack. Try again.')),
      },
    )
  }

  return (
    <StackDialog title="Add stack" titleId={titleId} onClose={onClose}>
      <form onSubmit={submit} className="sk-form">
        <label className="sk-field">
          <span>Name</span>
          <input value={name} onChange={(e) => setName(e.target.value)} autoFocus autoComplete="off" spellCheck={false} />
        </label>
        <label className="sk-field">
          <span>Compose file</span>
          <textarea className="sk-yaml-input" value={yaml} onChange={(e) => setYaml(e.target.value)} rows={10} spellCheck={false} />
        </label>
        <fieldset className="sk-env">
          <legend>Environment variables</legend>
          {env.map((row, i) => (
            <div className="sk-env__row" key={i}>
              <input aria-label={`Variable ${i + 1} name`} placeholder="NAME" value={row.key} onChange={(e) => setRow(i, { key: e.target.value })} autoComplete="off" spellCheck={false} />
              <input aria-label={`Variable ${i + 1} value`} placeholder="value" type="password" value={row.value} onChange={(e) => setRow(i, { value: e.target.value })} autoComplete="off" />
              <button type="button" className="sk-btn sk-btn--plain" aria-label={`Remove variable ${i + 1}`} onClick={() => setEnv((rows) => rows.filter((_, j) => j !== i))}>
                Remove
              </button>
            </div>
          ))}
          <button type="button" className="sk-btn sk-btn--plain" onClick={() => setEnv((rows) => [...rows, { key: '', value: '' }])}>
            Add variable
          </button>
        </fieldset>
        {problem && (
          <p role="alert" className="sk-error">
            {problem}
          </p>
        )}
        <div className="sk-dialog__actions">
          <button type="button" className="sk-btn sk-btn--plain" onClick={onClose}>
            Cancel
          </button>
          <button type="submit" className="sk-btn sk-btn--primary" disabled={create.isPending}>
            Create stack
          </button>
        </div>
      </form>
    </StackDialog>
  )
}
