import { useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { ApiError } from '../../shared/api/client.ts'
import { Button, Dialog } from '../../shared/ui/index.ts'
import { useAdoptDeployment } from './api.ts'
import { describeDeploymentError } from './deploymentErrors.ts'
import { EnvEditor, toPayload, validateEnv, YamlEditor } from './editor/index.ts'
import type { EnvEntry } from './api.ts'

/**
 * Adopts an unmanaged Compose project. First tries the files the project was started with; when the server
 * cannot read them (YAML_REQUIRED) the user pastes the YAML and variables instead.
 */
export function AdoptDeploymentDialog({ hostId, project, onClose }: { hostId: string; project: string; onClose: () => void }) {
  const navigate = useNavigate()
  const adopt = useAdoptDeployment(hostId)
  const [manual, setManual] = useState(false)
  const [yaml, setYaml] = useState('')
  const [env, setEnv] = useState<EnvEntry[]>([])
  const [problem, setProblem] = useState<string | null>(null)
  const [yamlError, setYamlError] = useState<{ message: string; details?: string[] } | null>(null)

  function submit(e: FormEvent) {
    e.preventDefault()
    setProblem(null)
    setYamlError(null)
    let body: { yaml?: string; env?: EnvEntry[] } = {}
    if (manual) {
      if (yaml.trim() === '') return setProblem('Enter the Compose file.')
      if (validateEnv(env).size > 0) return setProblem('Fix the highlighted variables first.')
      body = { yaml, env: toPayload(env) }
    }
    adopt.mutate(
      { project, ...body },
      {
        onSuccess: (deployment) => navigate(`/environments/${hostId}/deployments/${deployment.id}`),
        onError: (err) => {
          const code = err instanceof ApiError ? err.code : undefined
          if (code === 'YAML_REQUIRED') {
            setManual(true)
            setProblem('Shipir cannot read the Compose file of this project. Paste it below.')
          } else if (code === 'PATH_NOT_ALLOWED') {
            setManual(true)
            setProblem(describeDeploymentError(err, 'Shipir may not read the Compose file of this project. Paste it below.'))
          } else if (code === 'INVALID_COMPOSE') {
            const details = (err as ApiError).details
            setManual(true)
            setYamlError({ message: 'The Compose file is not valid.', details })
            setProblem(manual ? null : describeDeploymentError(err, 'The Compose file is not valid.') + ' Paste a corrected file below.')
          } else {
            setProblem(describeDeploymentError(err, 'Could not adopt the project. Try again.'))
          }
        },
      },
    )
  }

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()} title="Adopt deployment">
      <form onSubmit={submit} className="ui-form">
        <p className="ui-dialog-text">
          Shipir takes <strong>{project}</strong> under its management using its Compose file
          {manual ? ' as pasted below' : ' as found on the environment'}. Running containers are not restarted.
        </p>
        <p className="ui-hint">
          Relative bind mounts and <code>env_file</code> entries in a pasted Compose file resolve against Shipir&apos;s own stack directory
          on the next deploy, not the original project directory.
        </p>
        {manual && (
          <div className="ui-form">
            <div className="ui-form-field">
              <span>Compose file</span>
              <YamlEditor label="Compose file" value={yaml} onChange={setYaml} height="260px" error={yamlError} />
            </div>
            <EnvEditor value={env} onChange={setEnv} />
          </div>
        )}
        {problem && (
          <p role="alert" className="ui-error">
            {problem}
          </p>
        )}
        <div className="ui-dialog-actions">
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button variant="primary" type="submit" loading={adopt.isPending}>
            Adopt
          </Button>
        </div>
      </form>
    </Dialog>
  )
}
