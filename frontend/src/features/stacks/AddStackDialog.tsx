import { useId, useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { ApiError } from '../../shared/api/client.ts'
import { useCreateStack, type EnvEntry } from './api.ts'
import { EnvEditor, toPayload, validateEnv, YamlEditor } from './editor/index.ts'
import { describeStackError } from './stackErrors.ts'
import { StackDialog } from './StackDialog.tsx'
import { ComposeWizard } from './wizard/index.ts'
import './wizard/wizard.css'

const NAME_PATTERN = /^[a-z0-9][a-z0-9_-]*$/

/**
 * Create form: name, then either the Compose file (Monaco) or the form wizard, plus environment variables.
 * The wizard hands its generated YAML to the editor ("Continue in editor"); it is one-way.
 */
export function AddStackDialog({ hostId, onClose }: { hostId: string; onClose: () => void }) {
  const titleId = useId()
  const navigate = useNavigate()
  const create = useCreateStack(hostId)
  const [name, setName] = useState('')
  const [yaml, setYaml] = useState('')
  const [env, setEnv] = useState<EnvEntry[]>([])
  const [mode, setMode] = useState<'yaml' | 'wizard'>('yaml')
  const [problem, setProblem] = useState<string | null>(null)
  const [yamlError, setYamlError] = useState<{ message: string; details?: string[] } | null>(null)

  function continueInEditor(generated: string, envKeys: string[]) {
    setYaml(generated)
    setYamlError(null)
    // Referenced variables get an empty row to fill in; existing values stay.
    setEnv((current) => [...current, ...envKeys.filter((k) => !current.some((r) => r.key === k)).map((key) => ({ key, value: '' }))])
    setMode('yaml')
  }

  function submit(e: FormEvent) {
    e.preventDefault()
    if (mode !== 'yaml') return // Enter inside the wizard must not submit
    const trimmed = name.trim()
    if (!NAME_PATTERN.test(trimmed) || trimmed.length > 63) {
      setProblem('Use lowercase letters, digits, hyphens and underscores, up to 63 characters.')
      return
    }
    if (yaml.trim() === '') {
      setProblem('Enter the Compose file.')
      return
    }
    if (validateEnv(env).size > 0) {
      setProblem('Fix the highlighted variables first.')
      return
    }
    setProblem(null)
    setYamlError(null)
    const entries = toPayload(env)
    create.mutate(
      { name: trimmed, yaml, env: entries },
      {
        onSuccess: (stack) => navigate(`/environments/${hostId}/stacks/${stack.id}`),
        onError: (err) => {
          if (err instanceof ApiError && err.code === 'INVALID_COMPOSE') {
            setYamlError({ message: 'The Compose file is not valid.', details: err.details })
          } else {
            setProblem(describeStackError(err, 'Could not create the stack. Try again.'))
          }
        },
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
        <div className="wz-modes" role="radiogroup" aria-label="Editing mode">
          {([['wizard', 'Form wizard'], ['yaml', 'YAML']] as const).map(([value, label]) => (
            <label key={value} className="wz-mode">
              <input type="radio" name="compose-mode" checked={mode === value} onChange={() => setMode(value)} />
              {label}
            </label>
          ))}
        </div>
        <div hidden={mode !== 'wizard'}>
          <ComposeWizard onContinue={continueInEditor} />
        </div>
        <div className="sk-form" hidden={mode !== 'yaml'}>
          <div className="sk-field">
            <span>Compose file</span>
            <YamlEditor label="Compose file" value={yaml} onChange={setYaml} height="260px" error={yamlError} />
          </div>
          <EnvEditor value={env} onChange={setEnv} />
        </div>
        {problem && (
          <p role="alert" className="sk-error">
            {problem}
          </p>
        )}
        <div className="sk-dialog__actions">
          <button type="button" className="sk-btn sk-btn--plain" onClick={onClose}>
            Cancel
          </button>
          <button type="submit" className="sk-btn sk-btn--primary" disabled={create.isPending} hidden={mode !== 'yaml'}>
            Create stack
          </button>
        </div>
      </form>
    </StackDialog>
  )
}
