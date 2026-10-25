import { useState } from 'react'
import { Badge, Button, Card, Switch } from '../../shared/ui/index.ts'
import { useSetImageAutoUpdate, type ImageAutoUpdateInfo } from './api.ts'
import { describeDeploymentError } from './deploymentErrors.ts'
import './ImageAutoUpdatePanel.css'

/** Validates the optional daily window: both or neither, and different. Returns an error text or null. */
function validateWindow(start: string, end: string): string | null {
  if (!start && !end) return null
  if (!start || !end) return 'Set both a start and an end time, or clear both for any time.'
  if (start === end) return 'Start and end must be different.'
  return null
}

/** Scheduled image auto-update of a FILE deployment: on/off, optional daily UTC window, last automatic attempt. */
export function ImageAutoUpdatePanel({
  deploymentId,
  hostId,
  info,
  canWrite,
}: {
  deploymentId: string
  hostId: string
  info: ImageAutoUpdateInfo
  canWrite: boolean
}) {
  const save = useSetImageAutoUpdate(hostId)
  const [enabled, setEnabled] = useState(info.enabled)
  const [start, setStart] = useState(info.windowStart ?? '')
  const [end, setEnd] = useState(info.windowEnd ?? '')
  const [problem, setProblem] = useState<string | null>(null)
  const [saved, setSaved] = useState(false)
  const titleId = `image-auto-${deploymentId}`

  function submit() {
    setSaved(false)
    const invalid = validateWindow(start, end)
    if (invalid) {
      setProblem(invalid)
      return
    }
    setProblem(null)
    save.mutate(
      { deploymentId, enabled, windowStart: start || null, windowEnd: end || null },
      {
        onSuccess: () => setSaved(true),
        onError: (err) => setProblem(describeDeploymentError(err, 'Could not save the settings. Try again.')),
      },
    )
  }

  return (
    <Card className="sd-panel" role="region" aria-labelledby={titleId}>
      <h3 className="sd-panel__title" id={titleId}>
        Automatic image updates
      </h3>
      <p className="sd-muted">
        When enabled, Shipir pulls new images for this deployment and restarts it, but only when an update is available
        and only inside the update window. If the new containers are not healthy, the update is rolled back
        automatically. The deployment is locked while this runs, so a manual deploy meanwhile is rejected with a
        conflict.
      </p>
      <div className="ia-form">
        <Switch
          label="Automatic image updates"
          checked={enabled}
          disabled={!canWrite}
          onChange={(e) => {
            setEnabled(e.target.checked)
            setSaved(false)
          }}
        />
        <fieldset className="ia-window" disabled={!canWrite}>
          <legend>Update window (UTC)</legend>
          <div className="ia-window__times">
            <label className="ui-form-field">
              <span>Window start (UTC)</span>
              <input
                type="time"
                value={start}
                onChange={(e) => {
                  setStart(e.target.value)
                  setSaved(false)
                }}
              />
            </label>
            <label className="ui-form-field">
              <span>Window end (UTC)</span>
              <input
                type="time"
                value={end}
                onChange={(e) => {
                  setEnd(e.target.value)
                  setSaved(false)
                }}
              />
            </label>
          </div>
          {!start && !end ? (
            <p className="sd-muted">
              <strong>Any time</strong>: updates may run whenever one is available.
            </p>
          ) : (
            <p className="sd-muted">All times are UTC. An end before the start spans midnight; the end time is excluded.</p>
          )}
        </fieldset>
        {canWrite && (
          <div>
            <Button variant="secondary" onClick={submit} disabled={save.isPending}>
              Save
            </Button>
          </div>
        )}
        {saved && (
          <p role="status" className="sd-muted">
            Saved.
          </p>
        )}
        {problem && (
          <p role="alert" className="ui-error">
            {problem}
          </p>
        )}
        {info.lastAttemptAt && (
          <p className="sd-muted ia-last">
            <span>Last attempt: {new Date(info.lastAttemptAt).toLocaleString()}</span>
            {info.lastResult === 'APPLIED' && <Badge tone="ok">Applied</Badge>}
            {info.lastResult === 'FAILED' && <Badge tone="bad">Failed, rolled back if possible</Badge>}
          </p>
        )}
      </div>
    </Card>
  )
}
