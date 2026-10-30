import { useEffect, useState } from 'react'
import { ApiError } from '../../shared/api/client.ts'
import { useLoadDeploymentEnv, useUpdateDeployment, type EnvEntry, type ManagedDeploymentDetail } from './api.ts'
import { EnvEditor, toPayload, validateEnv, YamlEditor } from './editor/index.ts'
import { describeDeploymentError } from './deploymentErrors.ts'
import { Button, Card } from '../../shared/ui/index.ts'
import { useTranslation } from 'react-i18next'

/**
 * Compose file and environment variables of a deployment. Read-only until "Edit"; saving creates a draft
 * version, it does not deploy. Variable values stay on the server unless the user opens them.
 */
export function DeploymentContentPanel({
  deployment,
  hostId,
  canWrite,
  onEditingChange,
}: {
  deployment: ManagedDeploymentDetail
  hostId: string
  canWrite: boolean
  /** Called whenever edit mode starts or ends, so the page can block Deploy while edits are unsaved. */
  onEditingChange?: (editing: boolean) => void
}) {
  const { t } = useTranslation('deployments')
  const update = useUpdateDeployment(hostId)
  const loadEnv = useLoadDeploymentEnv(hostId)
  const [editing, setEditing] = useState(false)
  const [yaml, setYaml] = useState(deployment.yaml)
  /** null: values not opened, the server keeps the stored ones. */
  const [env, setEnv] = useState<EnvEntry[] | null>(null)
  const [error, setError] = useState<{ message: string; details?: string[] } | null>(null)
  const [saved, setSaved] = useState(false)
  const gitManaged = deployment.source === 'GIT'

  useEffect(() => {
    onEditingChange?.(editing)
    return () => onEditingChange?.(false)
  }, [editing, onEditingChange])

  function startEdit() {
    setYaml(deployment.yaml)
    setEnv(null)
    setError(null)
    setSaved(false)
    loadEnv.reset()
    setEditing(true)
  }

  function cancel() {
    setEditing(false)
    setError(null)
  }

  function openValues() {
    loadEnv.mutate(deployment.id, { onSuccess: (r) => setEnv(r.entries) })
  }

  function save() {
    if (env && validateEnv(env).size > 0) {
      setError({ message: t('DeploymentContentPanel.fixTheHighlightedVariablesFirst') })
      return
    }
    setError(null)
    update.mutate(
      { deploymentId: deployment.id, yaml, env: env ? toPayload(env) : null },
      {
        onSuccess: () => {
          setEditing(false)
          setSaved(true)
        },
        onError: (err) =>
          setError({
            message:
              err instanceof ApiError && err.code === 'INVALID_COMPOSE'
                ? t('DeploymentContentPanel.theComposeFileIsNot')
                : describeDeploymentError(err, t('DeploymentContentPanel.couldNotSaveTheChanges')),
            details: err instanceof ApiError && err.code === 'INVALID_COMPOSE' ? err.details : undefined,
          }),
      },
    )
  }

  return (
    <Card className="sd-panel">
      <div className="sd-panel__head">
        <h3 className="sd-panel__title">{t('DeploymentContentPanel.composeFile')}</h3>
        {canWrite && !editing && (
          <Button variant="secondary" onClick={startEdit}>
            {t('DeploymentContentPanel.edit')}
          </Button>
        )}
      </div>
      {saved && !editing && (
        <p role="status" className="sd-muted">
          {t('DeploymentContentPanel.savedAsADraftDeploy')}
        </p>
      )}
      {editing && (
        <p role="status" className="sd-muted">
          {t('DeploymentContentPanel.actionsPausedWhileEditing')}
        </p>
      )}
      {gitManaged && editing && <p className="sd-muted">{t('DeploymentContentPanel.theComposeFileComesFrom')}</p>}
      <YamlEditor
        label={t('DeploymentContentPanel.composeFile')}
        value={editing ? yaml : deployment.yaml}
        onChange={setYaml}
        readOnly={!editing || gitManaged}
        error={editing && error && error.details ? error : null}
      />

      <h3 className="sd-panel__title">{t('DeploymentContentPanel.environmentVariables')}</h3>
      {editing && env ? (
        <EnvEditor value={env} onChange={setEnv} />
      ) : (
        <>
          {deployment.envKeys.length === 0 ? (
            <p className="sd-muted">{t('DeploymentContentPanel.noVariables')}</p>
          ) : (
            <ul className="sd-keys" aria-label={t('DeploymentContentPanel.environmentVariableNames')}>
              {deployment.envKeys.map((k) => (
                <li key={k}>
                  <code>{k}</code>
                </li>
              ))}
            </ul>
          )}
          {editing && (
            <>
              <p className="sd-muted">{t('DeploymentContentPanel.storedValuesStayUnchangedUnless')}</p>
              {loadEnv.isError && (
                <p role="alert" className="ui-error">
                  {describeDeploymentError(loadEnv.error, t('DeploymentContentPanel.couldNotLoadTheVariables'))}
                </p>
              )}
              <div>
                <Button variant="secondary" onClick={openValues} disabled={loadEnv.isPending}>
                  {t('DeploymentContentPanel.editVariables')}
                </Button>
              </div>
            </>
          )}
        </>
      )}

      {editing && (
        <>
          {error && !error.details && (
            <p role="alert" className="ui-error">
              {error.message}
            </p>
          )}
          <div className="sd-panel__actions">
            <Button variant="ghost" onClick={cancel} disabled={update.isPending}>
              {t('DeploymentContentPanel.cancel')}
            </Button>
            <Button variant="primary" onClick={save} disabled={update.isPending}>
              {t('DeploymentContentPanel.save')}
            </Button>
          </div>
        </>
      )}
    </Card>
  )
}
