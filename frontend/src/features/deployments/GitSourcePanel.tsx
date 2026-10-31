import { useState } from 'react'
import { useSetAutoUpdate, type GitInfo } from './api.ts'
import { describePollError, describeDeploymentError } from './deploymentErrors.ts'
import { Button, Card, Switch, TextField } from '../../shared/ui/index.ts'
import { useTranslation } from 'react-i18next'
import { formatDateTime } from '../../shared/i18n/format.ts'

const DEFAULT_INTERVAL_MINUTES = 5

/** Repository, ref, commit and auto-update settings of a Git deployment. Pull & redeploy lives in the header actions. */
export function GitSourcePanel({ deploymentId, hostId, git, canWrite }: { deploymentId: string; hostId: string; git: GitInfo; canWrite: boolean }) {
  const { t } = useTranslation('deployments')
  const save = useSetAutoUpdate(hostId)
  const [enabled, setEnabled] = useState(git.autoUpdateEnabled)
  const [minutes, setMinutes] = useState(String(Math.round((git.autoUpdateIntervalSeconds ?? DEFAULT_INTERVAL_MINUTES * 60) / 60)))
  const [problem, setProblem] = useState<string | null>(null)
  const [saved, setSaved] = useState(false)
  // Resync with the server values (e.g. changed by another user) unless the user has unsaved edits.
  const serverKey = `${git.autoUpdateEnabled}|${git.autoUpdateIntervalSeconds ?? ''}`
  const [syncedKey, setSyncedKey] = useState(serverKey)
  const [dirty, setDirty] = useState(false)
  if (serverKey !== syncedKey) {
    setSyncedKey(serverKey)
    if (!dirty) {
      setEnabled(git.autoUpdateEnabled)
      setMinutes(String(Math.round((git.autoUpdateIntervalSeconds ?? DEFAULT_INTERVAL_MINUTES * 60) / 60)))
    }
  }

  function submit() {
    setSaved(false)
    const value = Number(minutes)
    if (!Number.isInteger(value) || value < 1) {
      setProblem(t('GitSourcePanel.theIntervalMustBeAt'))
      return
    }
    setProblem(null)
    save.mutate(
      { deploymentId, enabled, intervalSeconds: value * 60 },
      {
        onSuccess: () => {
          setSaved(true)
          setDirty(false)
        },
        onError: (err) => setProblem(describeDeploymentError(err, t('GitSourcePanel.couldNotSaveTheAuto'))),
      },
    )
  }

  return (
    <Card className="sd-panel" role="region" aria-labelledby={`git-${deploymentId}`}>
      <h3 className="sd-panel__title" id={`git-${deploymentId}`}>
        {t('GitSourcePanel.gitSource')}
      </h3>
      <dl className="sd-git">
        <dt>{t('GitSourcePanel.repository')}</dt>
        <dd>{git.repoUrl}</dd>
        <dt>{t('GitSourcePanel.branchOrTag')}</dt>
        <dd>{git.ref}</dd>
        <dt>{t('GitSourcePanel.filePath')}</dt>
        <dd>{git.composePath}</dd>
        <dt>{t('GitSourcePanel.commit')}</dt>
        <dd>{git.lastCommitSha ? <code>{git.lastCommitSha.slice(0, 7)}</code> : t('GitSourcePanel.notDeployedYet')}</dd>
        <dt>{t('GitSourcePanel.credential')}</dt>
        <dd>{git.credentialId ? t('GitSourcePanel.storedCredential') : t('GitSourcePanel.nonePublicRepository')}</dd>
      </dl>

      <div className="sd-auto">
        <Switch
          label={t('GitSourcePanel.autoUpdate')}
          checked={enabled}
          disabled={!canWrite}
          onChange={(e) => {
            setEnabled(e.target.checked)
            setSaved(false)
            setDirty(true)
          }}
        />
        <p className="sd-muted">{t('GitSourcePanel.checksTheRepositoryRegularlyAnd')}</p>
        <div className="sd-auto__interval">
          <TextField
            label={t('GitSourcePanel.checkEveryMinutes')}
            type="number"
            min={1}
            step={1}
            inputMode="numeric"
            value={minutes}
            disabled={!canWrite}
            onChange={(e) => {
              setMinutes(e.target.value)
              setSaved(false)
              setDirty(true)
            }}
          />
        </div>
        {canWrite && (
          <div>
            <Button variant="secondary" onClick={submit} disabled={save.isPending}>
              {t('GitSourcePanel.saveAutoUpdate')}
            </Button>
          </div>
        )}
        {saved && (
          <p role="status" className="sd-muted">
            {t('GitSourcePanel.saved')}
          </p>
        )}
        {problem && (
          <p role="alert" className="ui-error">
            {problem}
          </p>
        )}
        {git.lastPolledAt && <p className="sd-muted">{t('GitSourcePanel.lastChecked')} {formatDateTime(git.lastPolledAt)}</p>}
        {git.lastPollError && (
          <p role="alert" className="ui-error">
            {t('GitSourcePanel.lastCheckFailedPollerror', { pollError: describePollError(git.lastPollError) })}
          </p>
        )}
      </div>
    </Card>
  )
}
