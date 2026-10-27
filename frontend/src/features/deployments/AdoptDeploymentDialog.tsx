import { useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { ApiError } from '../../shared/api/client.ts'
import { Button, Dialog } from '../../shared/ui/index.ts'
import { useAdoptDeployment } from './api.ts'
import { describeDeploymentError } from './deploymentErrors.ts'
import { EnvEditor, toPayload, validateEnv, YamlEditor } from './editor/index.ts'
import type { EnvEntry } from './api.ts'
import { useTranslation } from 'react-i18next'
import { Trans } from '../../shared/i18n/Trans.tsx'

/**
 * Adopts an unmanaged Compose project. First tries the files the project was started with; when the server
 * cannot read them (YAML_REQUIRED) the user pastes the YAML and variables instead.
 */
export function AdoptDeploymentDialog({ hostId, project, onClose }: { hostId: string; project: string; onClose: () => void }) {
  const { t } = useTranslation('deployments')
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
      if (yaml.trim() === '') return setProblem(t('AdoptDeploymentDialog.enterTheComposeFile'))
      if (validateEnv(env).size > 0) return setProblem(t('AdoptDeploymentDialog.fixTheHighlightedVariablesFirst'))
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
            setProblem(t('AdoptDeploymentDialog.shipirCannotReadTheCompose'))
          } else if (code === 'PATH_NOT_ALLOWED') {
            setManual(true)
            setProblem(describeDeploymentError(err, t('AdoptDeploymentDialog.shipirMayNotReadThe')))
          } else if (code === 'RELATIVE_PATHS_NOT_SUPPORTED') {
            setManual(true)
            setProblem(describeDeploymentError(err, t('AdoptDeploymentDialog.theComposeFileUsesRelative')))
          } else if (code === 'INVALID_COMPOSE') {
            const details = (err as ApiError).details
            setManual(true)
            setYamlError({ message: t('AdoptDeploymentDialog.theComposeFileIsNot'), details })
            setProblem(manual ? null : describeDeploymentError(err, t('AdoptDeploymentDialog.theComposeFileIsNot')) + t('AdoptDeploymentDialog.pasteACorrectedFileBelow'))
          } else {
            setProblem(describeDeploymentError(err, t('AdoptDeploymentDialog.couldNotAdoptTheProject')))
          }
        },
      },
    )
  }

  return (
    <Dialog size="lg" open onOpenChange={(open) => !open && onClose()} title={t('AdoptDeploymentDialog.adoptDeployment')}>
      <form onSubmit={submit} className="ui-form">
        <p className="ui-dialog-text">
          <Trans t={t} i18nKey="AdoptDeploymentDialog.shipirTakesProjectUnderIts" values={{ project }} components={{ strong: <strong /> }} />
          {manual ? t('AdoptDeploymentDialog.asPastedBelow') : t('AdoptDeploymentDialog.asFoundOnTheEnvironment')}{t('AdoptDeploymentDialog.runningContainersAreNotRestarted')}
        </p>
        <p className="ui-hint">
          <Trans t={t} i18nKey="AdoptDeploymentDialog.projectsWhoseComposeFileUses" components={{ code: <code /> }} />
        </p>
        {manual && (
          <div className="ui-form">
            <div className="ui-form-field">
              <span>{t('AdoptDeploymentDialog.composeFile')}</span>
              <YamlEditor label={t('AdoptDeploymentDialog.composeFile2')} value={yaml} onChange={setYaml} height="260px" error={yamlError} />
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
            {t('AdoptDeploymentDialog.cancel')}
          </Button>
          <Button variant="primary" type="submit" loading={adopt.isPending}>
            {t('AdoptDeploymentDialog.adopt')}
          </Button>
        </div>
      </form>
    </Dialog>
  )
}
