import { useState } from 'react'
import { useSetAutoUpdate, type GitInfo } from './api.ts'
import { describePollError, describeStackError } from './stackErrors.ts'

const DEFAULT_INTERVAL_MINUTES = 5

/** Repository, ref, commit and auto-update settings of a Git stack. Pull & redeploy lives in the header actions. */
export function GitSourcePanel({ stackId, hostId, git, canWrite }: { stackId: string; hostId: string; git: GitInfo; canWrite: boolean }) {
  const save = useSetAutoUpdate(hostId)
  const [enabled, setEnabled] = useState(git.autoUpdateEnabled)
  const [minutes, setMinutes] = useState(String(Math.round((git.autoUpdateIntervalSeconds ?? DEFAULT_INTERVAL_MINUTES * 60) / 60)))
  const [problem, setProblem] = useState<string | null>(null)
  const [saved, setSaved] = useState(false)

  function submit() {
    setSaved(false)
    const value = Number(minutes)
    if (!Number.isInteger(value) || value < 1) {
      setProblem('The interval must be at least 1 minute.')
      return
    }
    setProblem(null)
    save.mutate(
      { stackId, enabled, intervalSeconds: value * 60 },
      {
        onSuccess: () => setSaved(true),
        onError: (err) => setProblem(describeStackError(err, 'Could not save the auto-update settings. Try again.')),
      },
    )
  }

  return (
    <section className="sd-panel" aria-labelledby={`git-${stackId}`}>
      <h3 className="sd-panel__title" id={`git-${stackId}`}>
        Git source
      </h3>
      <dl className="sd-git">
        <dt>Repository</dt>
        <dd>{git.repoUrl}</dd>
        <dt>Branch or tag</dt>
        <dd>{git.ref}</dd>
        <dt>File path</dt>
        <dd>{git.composePath}</dd>
        <dt>Commit</dt>
        <dd>{git.lastCommitSha ? <code>{git.lastCommitSha.slice(0, 7)}</code> : 'Not deployed yet'}</dd>
        <dt>Credential</dt>
        <dd>{git.credentialId ? 'Stored credential' : 'None (public repository)'}</dd>
      </dl>

      <div className="sd-auto">
        <label className="sd-auto__toggle">
          <span>Auto-update</span>
          <input
            type="checkbox"
            role="switch"
            checked={enabled}
            disabled={!canWrite}
            onChange={(e) => {
              setEnabled(e.target.checked)
              setSaved(false)
            }}
            aria-label="Auto-update"
          />
        </label>
        <p className="sd-muted">Checks the repository regularly and redeploys when the branch has a new commit.</p>
        <label className="sk-field sd-auto__interval">
          <span>Check every (minutes)</span>
          <input
            type="number"
            min={1}
            step={1}
            inputMode="numeric"
            value={minutes}
            disabled={!canWrite}
            onChange={(e) => {
              setMinutes(e.target.value)
              setSaved(false)
            }}
          />
        </label>
        {canWrite && (
          <div>
            <button type="button" className="sk-btn" onClick={submit} disabled={save.isPending}>
              Save auto-update
            </button>
          </div>
        )}
        {saved && (
          <p role="status" className="sd-muted">
            Saved.
          </p>
        )}
        {problem && (
          <p role="alert" className="sk-error">
            {problem}
          </p>
        )}
        {git.lastPolledAt && <p className="sd-muted">Last checked: {new Date(git.lastPolledAt).toLocaleString()}</p>}
        {git.lastPollError && (
          <p role="alert" className="sk-error">
            Last check failed: {describePollError(git.lastPollError)}
          </p>
        )}
      </div>
    </section>
  )
}
