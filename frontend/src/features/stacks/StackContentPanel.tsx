import { useState } from 'react'
import { ApiError } from '../../shared/api/client.ts'
import { useLoadStackEnv, useUpdateStack, type EnvEntry, type ManagedStackDetail } from './api.ts'
import { EnvEditor, toPayload, validateEnv, YamlEditor } from './editor/index.ts'
import { describeStackError } from './stackErrors.ts'

/**
 * Compose file and environment variables of a stack. Read-only until "Edit"; saving creates a draft
 * version, it does not deploy. Variable values stay on the server unless the user opens them.
 */
export function StackContentPanel({ stack, hostId, canWrite }: { stack: ManagedStackDetail; hostId: string; canWrite: boolean }) {
  const update = useUpdateStack(hostId)
  const loadEnv = useLoadStackEnv(hostId)
  const [editing, setEditing] = useState(false)
  const [yaml, setYaml] = useState(stack.yaml)
  /** null: values not opened, the server keeps the stored ones. */
  const [env, setEnv] = useState<EnvEntry[] | null>(null)
  const [error, setError] = useState<{ message: string; details?: string[] } | null>(null)
  const [saved, setSaved] = useState(false)
  const gitManaged = stack.source === 'GIT'

  function startEdit() {
    setYaml(stack.yaml)
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
    loadEnv.mutate(stack.id, { onSuccess: (r) => setEnv(r.entries) })
  }

  function save() {
    if (env && validateEnv(env).size > 0) {
      setError({ message: 'Fix the highlighted variables first.' })
      return
    }
    setError(null)
    update.mutate(
      { stackId: stack.id, yaml, env: env ? toPayload(env) : null },
      {
        onSuccess: () => {
          setEditing(false)
          setSaved(true)
        },
        onError: (err) =>
          setError({
            message:
              err instanceof ApiError && err.code === 'INVALID_COMPOSE'
                ? 'The Compose file is not valid.'
                : describeStackError(err, 'Could not save the changes. Try again.'),
            details: err instanceof ApiError && err.code === 'INVALID_COMPOSE' ? err.details : undefined,
          }),
      },
    )
  }

  return (
    <div className="sd-panel">
      <div className="sd-panel__head">
        <h3 className="sd-panel__title">Compose file</h3>
        {canWrite && !editing && (
          <button type="button" className="sk-btn" onClick={startEdit}>
            Edit
          </button>
        )}
      </div>
      {saved && !editing && (
        <p role="status" className="sd-muted">
          Saved as a draft. Deploy to apply the changes.
        </p>
      )}
      {gitManaged && editing && <p className="sd-muted">The Compose file comes from the Git repository. Only variables can be edited here.</p>}
      <YamlEditor
        label="Compose file"
        value={editing ? yaml : stack.yaml}
        onChange={setYaml}
        readOnly={!editing || gitManaged}
        error={editing && error && error.details ? error : null}
      />

      <h3 className="sd-panel__title">Environment variables</h3>
      {editing && env ? (
        <EnvEditor value={env} onChange={setEnv} />
      ) : (
        <>
          {stack.envKeys.length === 0 ? (
            <p className="sd-muted">No variables.</p>
          ) : (
            <ul className="sd-keys" aria-label="Environment variable names">
              {stack.envKeys.map((k) => (
                <li key={k}>
                  <code>{k}</code>
                </li>
              ))}
            </ul>
          )}
          {editing && (
            <>
              <p className="sd-muted">Stored values stay unchanged unless you edit the variables.</p>
              {loadEnv.isError && (
                <p role="alert" className="sk-error">
                  {describeStackError(loadEnv.error, 'Could not load the variables. Try again.')}
                </p>
              )}
              <div>
                <button type="button" className="sk-btn" onClick={openValues} disabled={loadEnv.isPending}>
                  Edit variables
                </button>
              </div>
            </>
          )}
        </>
      )}

      {editing && (
        <>
          {error && !error.details && (
            <p role="alert" className="sk-error">
              {error.message}
            </p>
          )}
          <div className="sd-panel__actions">
            <button type="button" className="sk-btn sk-btn--plain" onClick={cancel} disabled={update.isPending}>
              Cancel
            </button>
            <button type="button" className="sk-btn sk-btn--primary" onClick={save} disabled={update.isPending}>
              Save
            </button>
          </div>
        </>
      )}
    </div>
  )
}
