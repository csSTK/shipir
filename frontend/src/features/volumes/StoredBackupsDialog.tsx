import { Download } from 'lucide-react'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { dateTimeFormat } from '../../shared/i18n/format.ts'
import { Button, Dialog, IconButton } from '../../shared/ui/index.ts'
import { formatSize } from '../hosts/format.ts'
import { describeBackupError, downloadStoredBackup, useStoredBackups } from './backup.ts'
import './VolumeBackup.css'

const dateFormat = dateTimeFormat({ dateStyle: 'medium', timeStyle: 'short' })

interface Props {
  hostId: string
  volume: string
  onClose: () => void
}

/** Archives that scheduled backups stored on the Shipir server for one volume, each downloadable. */
export function StoredBackupsDialog({ hostId, volume, onClose }: Props) {
  const { t } = useTranslation('volumes')
  const stored = useStoredBackups(hostId, volume)
  const [error, setError] = useState<string | null>(null)

  async function download(name: string) {
    setError(null)
    try {
      await downloadStoredBackup(hostId, volume, name)
    } catch (e) {
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
                <IconButton icon={Download} aria-label={t('StoredBackupsDialog.downloadName', { name: b.name })} onClick={() => void download(b.name)} />
              </li>
            ))}
          </ul>
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
