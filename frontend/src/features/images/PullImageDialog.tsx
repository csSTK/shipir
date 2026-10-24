import { useState, type FormEvent } from 'react'
import { DeployOutput } from '../deployments/deploy-output/DeployOutput.tsx'
import { describePullError, usePullImage, useRefreshAfterPull } from './api.ts'
import './ImagesPage.css'
import { Button, Dialog } from '../../shared/ui/index.ts'

interface Props {
  hostId: string
  /** Prefills the reference, e.g. the tag of an image with an update. */
  initialReference?: string
  onClose: () => void
}

/** Pulls an image by reference and shows the streamed progress in place of the form. */
export function PullImageDialog({ hostId, initialReference = '', onClose }: Props) {
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
    <Dialog open onOpenChange={(open) => !open && onClose()} title="Pull image">
      {runId ? (
        <>
          <DeployOutput title={`Pulling ${reference.trim()}`} runId={runId} onFinished={() => void refresh()} />
          <div className="ui-dialog-actions">
            <Button variant="primary" onClick={onClose}>
              Close
            </Button>
          </div>
        </>
      ) : (
        <form className="ui-form" onSubmit={submit}>
          <label className="ui-form-field">
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
            <p role="alert" className="ui-error">
              {error}
            </p>
          )}
          <div className="ui-dialog-actions">
            <Button variant="ghost" onClick={onClose}>
              Cancel
            </Button>
            <Button variant="primary" type="submit" disabled={pull.isPending || !reference.trim()}>
              {pull.isPending ? 'Starting…' : 'Pull'}
            </Button>
          </div>
        </form>
      )}
    </Dialog>
  )
}
