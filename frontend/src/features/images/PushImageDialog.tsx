import { useState } from 'react'
import { DeployOutput } from '../deployments/deploy-output/DeployOutput.tsx'
import { Button, Dialog, Select } from '../../shared/ui/index.ts'
import { describePushError, usePushImage, useRefreshAfterPull, type Image } from './api.ts'
import './ImagesPage.css'

interface Props {
  hostId: string
  image: Image
  onClose: () => void
}

/** Confirms which tag to push, then shows the live push output. The stored registry login is applied by the server. */
export function PushImageDialog({ hostId, image, onClose }: Props) {
  const [reference, setReference] = useState(image.repoTags[0] ?? '')
  const [runId, setRunId] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const push = usePushImage(hostId)
  const refresh = useRefreshAfterPull(hostId)

  function submit() {
    setError(null)
    push.mutate(reference, {
      onSuccess: (r) => setRunId(r.runId),
      onError: (err) => setError(describePushError(err)),
    })
  }

  return (
    <Dialog
      open
      onOpenChange={(open) => !open && onClose()}
      title="Push image"
      description={runId ? undefined : 'Uploads the image to its registry using the stored login for that registry, if any.'}
    >
      {runId ? (
        <>
          <DeployOutput title={`Pushing ${reference}`} runId={runId} onFinished={() => void refresh()} />
          <div className="ui-dialog-actions">
            <Button variant="primary" onClick={onClose}>
              Close
            </Button>
          </div>
        </>
      ) : (
        <div className="ui-form">
          <Select label="Reference to push" value={reference} onChange={(e) => setReference(e.target.value)}>
            {image.repoTags.map((t) => (
              <option key={t} value={t}>
                {t}
              </option>
            ))}
          </Select>
          {error && (
            <p role="alert" className="ui-error">
              {error}
            </p>
          )}
          <div className="ui-dialog-actions">
            <Button variant="ghost" onClick={onClose}>
              Cancel
            </Button>
            <Button variant="primary" loading={push.isPending} disabled={!reference} onClick={submit}>
              Push
            </Button>
          </div>
        </div>
      )}
    </Dialog>
  )
}
