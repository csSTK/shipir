import { useState } from 'react'
import { Button, Dialog, FormField, Switch } from '../../shared/ui/index.ts'
import type { Volume } from './api.ts'
import { MAX_RESTORE_BYTES, MAX_RESTORE_LABEL, describeBackupError, restoreVolume } from './backup.ts'
import { ApiError } from '../../shared/api/client.ts'
import { useTranslation } from 'react-i18next'
import './VolumeBackup.css'

interface Props {
  hostId: string
  volume: Volume
  onClose: () => void
  onDone: (message: string) => void
}

/** Pick a tar, restore it onto the volume; a volume in use needs an explicit confirmation. */
export function RestoreVolumeDialog({ hostId, volume, onClose, onDone }: Props) {
  const { t } = useTranslation('volumes')
  const [file, setFile] = useState<File | null>(null)
  const [inUse, setInUse] = useState(volume.inUse)
  const [confirmed, setConfirmed] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [pending, setPending] = useState(false)

  function pick(f: File | null) {
    setFile(f)
    setError(f && f.size > MAX_RESTORE_BYTES ? t('RestoreVolumeDialog.thisFileIsLargerThan', { MAX_RESTORE_LABEL }) : null)
  }

  const tooBig = !!file && file.size > MAX_RESTORE_BYTES
  const ready = !!file && !tooBig && (!inUse || confirmed)

  async function submit() {
    if (!file || !ready) return
    setPending(true)
    setError(null)
    try {
      await restoreVolume(hostId, volume.name, file, inUse && confirmed)
      onDone(t('RestoreVolumeDialog.restoredNameIntoName2', { name: file.name, volume: volume.name }))
      onClose()
    } catch (e) {
      if (e instanceof ApiError && e.code === 'VOLUME_IN_USE') setInUse(true)
      setError(describeBackupError(e))
      setPending(false)
    }
  }

  return (
    <Dialog
      open
      onOpenChange={(open) => !open && onClose()}
      title={t('RestoreVolumeDialog.restoreName', { name: volume.name })}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            {t('RestoreVolumeDialog.cancel')}
          </Button>
          <Button variant="primary" disabled={!ready} loading={pending} onClick={() => void submit()}>
            {t('RestoreVolumeDialog.restore')}
          </Button>
        </>
      }
    >
      <div className="vbackup__stack">
        <p className="ui-dialog-text">
          {t('RestoreVolumeDialog.theArchiveIsOverlaidOnto', { MAX_RESTORE_LABEL })}
        </p>
        <FormField label={t('RestoreVolumeDialog.backupArchiveTar')}>
          {(control) => <input {...control} className="ui-control" type="file" accept=".tar,application/x-tar" onChange={(e) => pick(e.target.files?.[0] ?? null)} />}
        </FormField>
        {inUse && (
          <Switch
            label={t('RestoreVolumeDialog.volumeIsInUseRestore')}
            help={t('RestoreVolumeDialog.runningContainersMaySeeFiles')}
            checked={confirmed}
            onChange={(e) => setConfirmed(e.target.checked)}
          />
        )}
        {error && (
          <p role="alert" className="ui-error">
            {error}
          </p>
        )}
      </div>
    </Dialog>
  )
}
