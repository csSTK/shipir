import { useId, useState, type FormEvent } from 'react'
import { DeployOutput } from '../deployments/deploy-output/DeployOutput.tsx'
import { DeploymentDialog } from '../deployments/DeploymentDialog.tsx'
import { describePullError, usePullImage, useRefreshAfterPull } from './api.ts'
import './ImagesPage.css'

interface Props {
  hostId: string
  /** Prefills the reference, e.g. the tag of an image with an update. */
  initialReference?: string
  onClose: () => void
}

/** Pulls an image by reference and shows the streamed progress in place of the form. */
export function PullImageDialog({ hostId, initialReference = '', onClose }: Props) {
  const titleId = useId()
  const [reference, setReference] = useState(initialReference)
  const [runId, setRunId] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const pull = usePullImage(hostId)
  const refresh = useRefreshAfterPull(hostId)

  function submit(e: FormEvent) {
    e.preventDefault()
    const ref = reference.trim()
    if (!ref) return
    setError(null)
    pull.mutate(ref, {
      onSuccess: (r) => setRunId(r.runId),
      onError: (err) => setError(describePullError(err)),
    })
  }

  return (
    <DeploymentDialog title="Pull image" titleId={titleId} onClose={onClose}>
      {runId ? (
        <>
          <DeployOutput title={`Pulling ${reference.trim()}`} runId={runId} onFinished={() => void refresh()} />
          <div className="sk-dialog__actions">
            <button type="button" className="sk-btn sk-btn--primary" onClick={onClose}>
              Close
            </button>
          </div>
        </>
      ) : (
        <form className="sk-form" onSubmit={submit}>
          <label className="sk-field">
            Image reference
            <input
              value={reference}
              onChange={(e) => setReference(e.target.value)}
              placeholder="nginx:1.27"
              autoComplete="off"
              autoCapitalize="off"
              spellCheck={false}
              autoFocus
            />
          </label>
          {error && (
            <p role="alert" className="sk-error">
              {error}
            </p>
          )}
          <div className="sk-dialog__actions">
            <button type="button" className="sk-btn sk-btn--plain" onClick={onClose}>
              Cancel
            </button>
            <button type="submit" className="sk-btn sk-btn--primary" disabled={pull.isPending || !reference.trim()}>
              {pull.isPending ? 'Starting…' : 'Pull'}
            </button>
          </div>
        </form>
      )}
    </DeploymentDialog>
  )
}
