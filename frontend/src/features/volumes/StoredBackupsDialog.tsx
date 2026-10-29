import { Download, RotateCcw } from 'lucide-react'
import { useQueryClient } from '@tanstack/react-query'
import { useEffect, useId, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { dateFormat } from './dateFormat.ts'
import { ApiError } from '../../shared/api/client.ts'
import { Button, Dialog, IconButton, Switch } from '../../shared/ui/index.ts'
import { formatSize } from '../hosts/format.ts'
import { describeBackupError, downloadStoredBackup, restoreStoredBackup, useStoredBackups } from './backup.ts'
import './VolumeBackup.css'

interface Props {
  hostId: string
  volume: string
  /** The volume is known to be mounted by a container, so a restore needs the confirmation up front. */
  inUse?: boolean
  onClose: () => void
  onDone?: (message: string) => void
}

/** Archives that scheduled backups stored on the Shipir server for one volume, each downloadable or restorable. */
export function StoredBackupsDialog({ hostId, volume, inUse: volumeInUse = false, onClose, onDone }: Props) {
  const { t } = useTranslation('volumes')
  const stored = useStoredBackups(hostId, volume)
  const queryClient = useQueryClient()
  const [error, setError] = useState<string | null>(null)
  const [chosen, setChosen] = useState<string | null>(null)
  const [inUse, setInUse] = useState(volumeInUse)
  const [confirmed, setConfirmed] = useState(false)
  const [pending, setPending] = useState(false)

  const titleId = useId()
  const titleRef = useRef<HTMLParagraphElement>(null)
  const rowButtons = useRef(new Map<string, HTMLButtonElement>())

  useEffect(() => {
    if (chosen) titleRef.current?.focus()
  }, [chosen])

  function choose(name: string | null) {
    if (name === null && chosen) rowButtons.current.get(chosen)?.focus()
    setChosen(name)
    setConfirmed(false)
    setError(null)
  }

  /** The archive was deleted or pruned meanwhile: drop its row. */
  function reloadIfGone(e: unknown) {
    if (!(e instanceof ApiError) || e.code !== 'STORED_BACKUP_NOT_FOUND') return false
    void queryClient.invalidateQueries({ queryKey: ['hosts', hostId, 'volumes', volume, 'stored-backups'] })
    return true
  }

  async function restore() {
    if (!chosen) return
    setPending(true)
    setError(null)
    try {
      await restoreStoredBackup(hostId, volume, chosen, inUse && confirmed)
      onDone?.(t('StoredBackupsDialog.restoredNameIntoVolume', { name: chosen, volume }))
      onClose()
    } catch (e) {
      if (e instanceof ApiError && e.code === 'VOLUME_IN_USE') setInUse(true)
      if (reloadIfGone(e)) setChosen(null)
      setError(describeBackupError(e))
      setPending(false)
    }
  }

  async function download(name: string) {
    setError(null)
    try {
      await downloadStoredBackup(hostId, volume, name)
    } catch (e) {
      reloadIfGone(e)
      setError(describeBackupError(e))
    }
  }

  return (
    <Dialog
      size="lg"
      open
      onOpenChange={(open) => !open && onClose()}
      title={t('StoredBackupsDialog.storedBackupsOfVolume', { volume })}
      description={t('StoredBackupsDialog.scheduledBackupsAreKeptOn')}
    >
      <div className="vbackup__stack">
        {stored.isPending ? (
          <p className="ui-hint">{t('StoredBackupsDialog.loading')}</p>
        ) : stored.error ? (
          <p role="alert" className="ui-error">
            {describeBackupError(stored.error)}
          </p>
        ) : stored.data.length === 0 ? (
          <p className="ui-hint">{t('StoredBackupsDialog.noStoredBackupsYet')}</p>
        ) : (
          <ul className="vbackup__list">
            {stored.data.map((b) => (
              <li key={b.name} className="vbackup__item">
                <div className="vbackup__item-text">
                  <strong className="vbackup__file">{b.name}</strong>
                  <span className="ui-hint">
                    <time dateTime={b.createdAt}>{dateFormat.format(new Date(b.createdAt))}</time> · {formatSize(b.sizeBytes)}
                  </span>
                </div>
                <IconButton
                  ref={(el) => {
                    if (el) rowButtons.current.set(b.name, el)
                    else rowButtons.current.delete(b.name)
                  }}
                  icon={RotateCcw}
                  aria-label={t('StoredBackupsDialog.restoreName', { name: b.name })}
                  disabled={pending}
                  onClick={() => choose(b.name)}
                />
                <IconButton icon={Download} aria-label={t('StoredBackupsDialog.downloadName', { name: b.name })} onClick={() => void download(b.name)} />
              </li>
            ))}
          </ul>
        )}
        {chosen && (
          <div className="vbackup__confirm" role="group" aria-labelledby={titleId}>
            <p id={titleId} ref={titleRef} tabIndex={-1} className="ui-dialog-text vbackup__confirm-title">{t('StoredBackupsDialog.restoreNameIntoVolume', { name: chosen, volume })}</p>
            <p className="ui-hint">{t('StoredBackupsDialog.theArchiveIsOverlaidOnto')}</p>
            {inUse && (
              <Switch
                label={t('RestoreVolumeDialog.volumeIsInUseRestore')}
                help={t('RestoreVolumeDialog.runningContainersMaySeeFiles')}
                checked={confirmed}
                onChange={(e) => setConfirmed(e.target.checked)}
              />
            )}
            <div className="ui-dialog-actions">
              <Button variant="ghost" disabled={pending} onClick={() => choose(null)}>
                {t('RestoreVolumeDialog.cancel')}
              </Button>
              <Button variant="primary" loading={pending} disabled={inUse && !confirmed} onClick={() => void restore()}>
                {t('RestoreVolumeDialog.restore')}
              </Button>
            </div>
          </div>
        )}
        {error && (
          <p role="alert" className="ui-error">
            {error}
          </p>
        )}
        <div className="ui-dialog-actions">
          <Button variant="ghost" onClick={onClose}>
            {t('StoredBackupsDialog.close')}
          </Button>
        </div>
      </div>
    </Dialog>
  )
}
