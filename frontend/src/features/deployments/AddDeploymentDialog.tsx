import { useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { ApiError } from '../../shared/api/client.ts'
import { useCurrentUser } from '../auth/index.ts'
import { useCreateDeployment, type EnvEntry } from './api.ts'
import { EnvEditor, toPayload, validateEnv, YamlEditor } from './editor/index.ts'
import { describeDeploymentError } from './deploymentErrors.ts'
import { GitSourceFields, type GitFormValue } from './GitSourceFields.tsx'
import { ComposeWizard, referencedEnvKeys, specToState, type WizardState } from './wizard/index.ts'
import { TemplatePicker, type AppTemplate } from './templates/index.ts'
import './wizard/wizard.css'
import './templates/templates.css'
import { Button, Dialog } from '../../shared/ui/index.ts'

const EMPTY_GIT_FORM: GitFormValue = { repoUrl: '', ref: 'main', composePath: 'compose.yaml', credentialId: '' }
const NAME_PATTERN = /^[a-z0-9][a-z0-9_-]*$/

/**
 * Create form: name, then either the Compose file (Monaco) or the form wizard, plus environment variables.
 * The wizard hands its generated YAML to the editor ("Continue in editor"); it is one-way.
 */
export function AddDeploymentDialog({ hostId, onClose }: { hostId: string; onClose: () => void }) {
  const navigate = useNavigate()
  const create = useCreateDeployment(hostId)
  const [name, setName] = useState('')
  const [yaml, setYaml] = useState('')
  const [env, setEnv] = useState<EnvEntry[]>([])
  const { data: user } = useCurrentUser()
  const [source, setSource] = useState<'file' | 'git'>('file')
  const [git, setGit] = useState<GitFormValue>(EMPTY_GIT_FORM)
  const [mode, setMode] = useState<'template' | 'yaml' | 'wizard'>('yaml')
  const [template, setTemplate] = useState<AppTemplate | null>(null)
  const [seed, setSeed] = useState<{ key: number; state?: WizardState }>({ key: 0 })
  const [problem, setProblem] = useState<string | null>(null)
  const [yamlError, setYamlError] = useState<{ message: string; details?: string[] } | null>(null)

  /** Adds an empty row per key that has none yet; existing values stay. */
  function addEnvKeys(keys: string[]) {
    setEnv((current) => [...current, ...keys.filter((k, i) => keys.indexOf(k) === i && !current.some((r) => r.key === k)).map((key) => ({ key, value: '' }))])
  }

  function continueInEditor(generated: string, envKeys: string[]) {
    setYaml(generated)
    setYamlError(null)
    addEnvKeys(envKeys)
    setMode('yaml')
  }

  /** Fills the wizard from the template and pre-creates the env rows it needs; the wizard then works as usual. */
  function pickTemplate(t: AppTemplate) {
    const state = specToState(t.spec)
    setTemplate(t)
    setSeed((current) => ({ key: current.key + 1, state }))
    addEnvKeys([...t.env.map((h) => h.key), ...referencedEnvKeys(state)])
    setName((current) => (current.trim() === '' ? t.id : current))
    setMode('wizard')
  }

  function submit(e: FormEvent) {
    e.preventDefault()
    if (source === 'file' && mode !== 'yaml') return // Enter inside the wizard must not submit
    const trimmed = name.trim()
    if (!NAME_PATTERN.test(trimmed) || trimmed.length > 63) {
      setProblem('Use lowercase letters, digits, hyphens and underscores, up to 63 characters.')
      return
    }
    if (source === 'git') {
      const repoUrl = git.repoUrl.trim()
      const ref = git.ref.trim()
      const composePath = git.composePath.trim()
      if (!repoUrl) return setProblem('Enter the repository URL.')
      if (!ref) return setProblem('Enter a branch or tag.')
      if (!composePath) return setProblem('Enter the path of the Compose file in the repository.')
      if (validateEnv(env).size > 0) return setProblem('Fix the highlighted variables first.')
      setProblem(null)
      create.mutate(
        { source: 'GIT', name: trimmed, repoUrl, ref, composePath, ...(git.credentialId ? { credentialId: git.credentialId } : {}), env: toPayload(env) },
        {
          onSuccess: (deployment) => navigate(`/environments/${hostId}/deployments/${deployment.id}`),
          onError: (err) => setProblem(describeDeploymentError(err, 'Could not create the deployment. Try again.')),
        },
      )
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
        onSuccess: (deployment) => navigate(`/environments/${hostId}/deployments/${deployment.id}`),
        onError: (err) => {
          if (err instanceof ApiError && err.code === 'INVALID_COMPOSE') {
            setYamlError({ message: 'The Compose file is not valid.', details: err.details })
          } else {
            setProblem(describeDeploymentError(err, 'Could not create the deployment. Try again.'))
          }
        },
      },
    )
  }

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()} title="Add deployment">
      <form onSubmit={submit} className="ui-form">
        <label className="ui-form-field">
          <span>Name</span>
          <input value={name} onChange={(e) => setName(e.target.value)} autoFocus autoComplete="off" spellCheck={false} />
        </label>
        <div className="wz-modes" role="radiogroup" aria-label="Source">
          {([['file', 'New file'], ['git', 'Git repository']] as const).map(([value, label]) => (
            <label key={value} className="wz-mode">
              <input type="radio" name="deployment-source" checked={source === value} onChange={() => setSource(value)} />
              {label}
            </label>
          ))}
        </div>
        {source === 'git' && (
          <>
            <GitSourceFields value={git} onChange={setGit} isAdmin={user?.role === 'ADMIN'} />
            <EnvEditor value={env} onChange={setEnv} />
          </>
        )}
        <div className="wz-modes" role="radiogroup" aria-label="Editing mode" hidden={source !== 'file'}>
          {([['template', 'From template'], ['wizard', 'Form wizard'], ['yaml', 'YAML']] as const).map(([value, label]) => (
            <label key={value} className="wz-mode">
              <input type="radio" name="compose-mode" checked={mode === value} onChange={() => setMode(value)} />
              {label}
            </label>
          ))}
        </div>
        {source === 'file' && mode === 'template' && <TemplatePicker onSelect={pickTemplate} selectedId={template?.id} />}
        <div hidden={source !== 'file' || mode !== 'wizard'}>
          {template && template.env.length > 0 && (
            <div className="tp-vars">
              <p className="tp-vars__title">Variables for {template.name}</p>
              <ul className="tp-vars__list">
                {template.env.map((h) => (
                  <li key={h.key}>
                    <code>{h.key}</code>: {h.description}
                    {h.secret ? ' (secret)' : ''}
                  </li>
                ))}
              </ul>
              <p className="tp-vars__note">Fill in their values after Continue in editor.</p>
            </div>
          )}
          <ComposeWizard key={seed.key} initial={seed.state} onContinue={continueInEditor} />
        </div>
        <div className="ui-form" hidden={source !== 'file' || mode !== 'yaml'}>
          <div className="ui-form-field">
            <span>Compose file</span>
            <YamlEditor label="Compose file" value={yaml} onChange={setYaml} height="260px" error={yamlError} />
          </div>
          <EnvEditor value={env} onChange={setEnv} />
        </div>
        {problem && (
          <p role="alert" className="ui-error">
            {problem}
          </p>
        )}
        <div className="ui-dialog-actions">
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button variant="primary" type="submit" disabled={create.isPending} hidden={source === 'file' && mode !== 'yaml'}>
            Create deployment
          </Button>
        </div>
      </form>
    </Dialog>
  )
}
