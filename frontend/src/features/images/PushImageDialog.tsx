import { useState } from 'react'
import { DeployOutput } from '../deployments/deploy-output/DeployOutput.tsx'
import { Button, Dialog, Select } from '../../shared/ui/index.ts'
import { describePushError, usePushImage, useRefreshAfterPull, type Image } from './api.ts'
import { useTranslation } from 'react-i18next'
import './ImagesPage.css'

interface Props {
  hostId: string
  image: Image
  onClose: () => void
}

/** Confirms which tag to push, then shows the live push output. The stored registry login is applied by the server. */
export function PushImageDialog({ hostId, image, onClose }: Props) {
  const { t: tr } = useTranslation('images')
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
      size="lg"
      open
      onOpenChange={(open) => !open && onClose()}
      title={tr('PushImageDialog.pushImage')}
      description={runId ? undefined : tr('PushImageDialog.uploadsTheImageToIts')}
    >
      {runId ? (
        <>
          <DeployOutput title={tr('PushImageDialog.pushingReference', { reference })} runId={runId} onFinished={() => void refresh()} />
          <div className="ui-dialog-actions">
            <Button variant="primary" onClick={onClose}>
              {tr('PushImageDialog.close')}
            </Button>
          </div>
        </>
      ) : (
        <div className="ui-form">
          <Select label={tr('PushImageDialog.referenceToPush')} value={reference} onChange={(e) => setReference(e.target.value)}>
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
              {tr('PushImageDialog.cancel')}
            </Button>
            <Button variant="primary" loading={push.isPending} disabled={!reference} onClick={submit}>
              {tr('PushImageDialog.push')}
            </Button>
          </div>
        </div>
      )}
    </Dialog>
  )
}
