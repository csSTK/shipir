import { useId, useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { ApiError } from '../../shared/api/client.ts'
import { useCurrentUser } from '../auth/index.ts'
import { useCreateDeployment, type EnvEntry } from './api.ts'
import { EnvEditor, toPayload, validateEnv, YamlEditor } from './editor/index.ts'
import { describeDeploymentError } from './deploymentErrors.ts'
import { GitSourceFields, type GitFormValue } from './GitSourceFields.tsx'
import { DeploymentDialog } from './DeploymentDialog.tsx'
import { ComposeWizard } from './wizard/index.ts'
import './wizard/wizard.css'

const EMPTY_GIT_FORM: GitFormValue = { repoUrl: '', ref: 'main', composePath: 'compose.yaml', credentialId: '' }
const NAME_PATTERN = /^[a-z0-9][a-z0-9_-]*$/

/**
 * Create form: name, then either the Compose file (Monaco) or the form wizard, plus environment variables.
 * The wizard hands its generated YAML to the editor ("Continue in editor"); it is one-way.
 */
export function AddDeploymentDialog({ hostId, onClose }: { hostId: string; onClose: () => void }) {
  const titleId = useId()
  const navigate = useNavigate()
  const create = useCreateDeployment(hostId)
  const [name, setName] = useState('')
  const [yaml, setYaml] = useState('')
  const [env, setEnv] = useState<EnvEntry[]>([])
  const { data: user } = useCurrentUser()
  const [source, setSource] = useState<'file' | 'git'>('file')
  const [git, setGit] = useState<GitFormValue>(EMPTY_GIT_FORM)
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
    <DeploymentDialog title="Add deployment" titleId={titleId} onClose={onClose}>
      <form onSubmit={submit} className="sk-form">
        <label className="sk-field">
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
          {([['wizard', 'Form wizard'], ['yaml', 'YAML']] as const).map(([value, label]) => (
            <label key={value} className="wz-mode">
              <input type="radio" name="compose-mode" checked={mode === value} onChange={() => setMode(value)} />
              {label}
            </label>
          ))}
        </div>
        <div hidden={source !== 'file' || mode !== 'wizard'}>
          <ComposeWizard onContinue={continueInEditor} />
        </div>
        <div className="sk-form" hidden={source !== 'file' || mode !== 'yaml'}>
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
          <button type="submit" className="sk-btn sk-btn--primary" disabled={create.isPending} hidden={source === 'file' && mode !== 'yaml'}>
            Create deployment
          </button>
        </div>
      </form>
    </DeploymentDialog>
  )
}
