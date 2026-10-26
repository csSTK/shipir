import { useState } from 'react'
import { Badge, Button, Card, Switch } from '../../shared/ui/index.ts'
import { useSetImageAutoUpdate, type ImageAutoUpdateInfo } from './api.ts'
import { describeDeploymentError } from './deploymentErrors.ts'
import { useTranslation } from 'react-i18next'
import { Trans } from '../../shared/i18n/Trans.tsx'
import i18n from 'i18next'
import { formatDateTime } from '../../shared/i18n/format.ts'
import './ImageAutoUpdatePanel.css'

/** Validates the optional daily window: both or neither, and different. Returns an error text or null. */
function validateWindow(start: string, end: string): string | null {
  if (!start && !end) return null
  if (!start || !end) return i18n.t('deployments:ImageAutoUpdatePanel.setBothAStartAnd')
  if (start === end) return i18n.t('deployments:ImageAutoUpdatePanel.startAndEndMustBe')
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
  const { t } = useTranslation('deployments')
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
        onError: (err) => setProblem(describeDeploymentError(err, t('ImageAutoUpdatePanel.couldNotSaveTheSettings'))),
      },
    )
  }

  return (
    <Card className="sd-panel" role="region" aria-labelledby={titleId}>
      <h3 className="sd-panel__title" id={titleId}>
        {t('ImageAutoUpdatePanel.automaticImageUpdates')}
      </h3>
      <p className="sd-muted">
        {t('ImageAutoUpdatePanel.whenEnabledShipirPullsNew')}
      </p>
      <div className="ia-form">
        <Switch
          label={t('ImageAutoUpdatePanel.automaticImageUpdates')}
          checked={enabled}
          disabled={!canWrite}
          onChange={(e) => {
            setEnabled(e.target.checked)
            setSaved(false)
          }}
        />
        <fieldset className="ia-window" disabled={!canWrite}>
          <legend>{t('ImageAutoUpdatePanel.updateWindowUtc')}</legend>
          <div className="ia-window__times">
            <label className="ui-form-field">
              <span>{t('ImageAutoUpdatePanel.windowStartUtc')}</span>
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
              <span>{t('ImageAutoUpdatePanel.windowEndUtc')}</span>
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
              <Trans t={t} i18nKey="ImageAutoUpdatePanel.anyTimeUpdatesMayRun" components={{ strong: <strong /> }} />
            </p>
          ) : (
            <p className="sd-muted">{t('ImageAutoUpdatePanel.allTimesAreUtcAn')}</p>
          )}
        </fieldset>
        {canWrite && (
          <div>
            <Button variant="secondary" onClick={submit} disabled={save.isPending}>
              {t('ImageAutoUpdatePanel.save')}
            </Button>
          </div>
        )}
        {saved && (
          <p role="status" className="sd-muted">
            {t('ImageAutoUpdatePanel.saved')}
          </p>
        )}
        {problem && (
          <p role="alert" className="ui-error">
            {problem}
          </p>
        )}
        {info.lastAttemptAt && (
          <p className="sd-muted ia-last">
            <span>{t('ImageAutoUpdatePanel.lastAttempt')} {formatDateTime(info.lastAttemptAt)}</span>
            {info.lastResult === 'APPLIED' && <Badge tone="ok">{t('ImageAutoUpdatePanel.applied')}</Badge>}
            {info.lastResult === 'FAILED' && <Badge tone="bad">{t('ImageAutoUpdatePanel.failedRolledBackIfPossible')}</Badge>}
          </p>
        )}
      </div>
    </Card>
  )
}
