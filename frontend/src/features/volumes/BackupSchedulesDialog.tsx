import { Archive, Trash2 } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { Button, ConfirmDialog, Dialog, IconButton, Select, TextField } from '../../shared/ui/index.ts'
import type { Volume } from './api.ts'
import { describeBackupError, type BackupSchedule, useBackupSchedules, useCreateSchedule, useDeleteSchedule } from './backup.ts'
import { StoredBackupsDialog } from './StoredBackupsDialog.tsx'
import { LastBackupBadge } from './LastBackupBadge.tsx'
import { useTranslation } from 'react-i18next'
import { Trans } from '../../shared/i18n/Trans.tsx'
import i18n from 'i18next'
import './VolumeBackup.css'

interface Props {
  hostId: string
  volumes: Volume[]
  initialVolume?: string
  onClose: () => void
  onDone?: (message: string) => void
}

const rangeError = (raw: string, max: number) => {
  const n = Number(raw)
  return Number.isInteger(n) && n >= 1 && n <= max ? null : i18n.t('volumes:BackupSchedulesDialog.enter1ToMaxHours', { max })
}

/** Lists the environment's backup schedules and adds or removes them. */
export function BackupSchedulesDialog({ hostId, volumes, initialVolume, onClose, onDone }: Props) {
  const { t } = useTranslation('volumes')
  const schedules = useBackupSchedules(hostId)
  const create = useCreateSchedule(hostId)
  const remove = useDeleteSchedule(hostId)
  const [volume, setVolume] = useState(initialVolume ?? volumes[0]?.name ?? '')
  const [interval, setIntervalHours] = useState('24')
  const [keep, setKeep] = useState('7')
  const [touched, setTouched] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [storedFor, setStoredFor] = useState<string | null>(null)
  const [deleting, setDeleting] = useState<BackupSchedule | null>(null)

  const intervalError = rangeError(interval, 8760)
  const keepError = Number.isInteger(Number(keep)) && Number(keep) >= 1 && Number(keep) <= 100 ? null : t('BackupSchedulesDialog.enter1To100Backups')
  const selected = volumes.find((v) => v.name === volume)

  function submit(e: FormEvent) {
    e.preventDefault()
    setTouched(true)
    if (!volume || intervalError || keepError) return
    setError(null)
    create.mutate(
      { volumeName: volume, intervalHours: Number(interval), keepCount: Number(keep) },
      { onError: (err) => setError(describeBackupError(err)) },
    )
  }

  return (
    <Dialog size="lg" open onOpenChange={(open) => !open && onClose()} title={t('BackupSchedulesDialog.backupSchedules')} description={t('BackupSchedulesDialog.automaticBackupsAreStoredOn')}>
      <div className="vbackup__stack">
        {schedules.isPending ? (
          <p className="ui-hint">{t('BackupSchedulesDialog.loading')}</p>
        ) : schedules.error ? (
          <p role="alert" className="ui-error">
            {describeBackupError(schedules.error)}
          </p>
        ) : schedules.data.length === 0 ? (
          <p className="ui-hint">{t('BackupSchedulesDialog.noSchedulesYet')}</p>
        ) : (
          <ul className="vbackup__list">
            {schedules.data.map((s) => (
              <li key={s.id} className="vbackup__item">
                <div className="vbackup__item-text">
                  <Trans t={t} i18nKey="BackupSchedulesDialog.volumenameEveryIntervalhoursHKeep" values={{ volumeName: s.volumeName, intervalHours: s.intervalHours, keepCount: s.keepCount }} components={{ strong: <strong />, span: <span className="ui-hint" /> }} />
                </div>
                <LastBackupBadge status={s.lastStatus} at={s.lastRunAt} />
                <IconButton
                  icon={Archive}
                  aria-label={t('BackupSchedulesDialog.storedBackupsOfVolumename', { volumeName: s.volumeName })}
                  onClick={() => setStoredFor(s.volumeName)}
                />
                <IconButton
                  icon={Trash2}
                  variant="danger"
                  aria-label={t('BackupSchedulesDialog.deleteScheduleForVolumename', { volumeName: s.volumeName })}
                  onClick={() => setDeleting(s)}
                />
              </li>
            ))}
          </ul>
        )}

        <form className="ui-form" onSubmit={submit} noValidate>
          <Select label={t('BackupSchedulesDialog.volume')} value={volume} onChange={(e) => setVolume(e.target.value)}>
            {volumes.map((v) => (
              <option key={v.name} value={v.name}>
                {v.name}
              </option>
            ))}
          </Select>
          {volumes.length === 0 && <p className="ui-hint">{t('BackupSchedulesDialog.noVolumesYet')}</p>}
          {selected?.inUse && <p className="ui-hint">{t('BackupSchedulesDialog.thisVolumeIsInUse')}</p>}
          <div className="vbackup__row">
            <TextField label={t('BackupSchedulesDialog.intervalHours')} type="number" min={1} max={8760} value={interval} onChange={(e) => setIntervalHours(e.target.value)} error={touched ? intervalError : null} />
            <TextField label={t('BackupSchedulesDialog.keepNewest')} type="number" min={1} max={100} value={keep} onChange={(e) => setKeep(e.target.value)} error={touched ? keepError : null} />
          </div>
          {error && (
            <p role="alert" className="ui-error">
              {error}
            </p>
          )}
          <div className="ui-dialog-actions">
            <Button variant="ghost" onClick={onClose}>
              {t('BackupSchedulesDialog.close')}
            </Button>
            <Button variant="primary" type="submit" disabled={volumes.length === 0} loading={create.isPending}>
              {t('BackupSchedulesDialog.addSchedule')}
            </Button>
          </div>
        </form>
      </div>
      {storedFor && (
        <StoredBackupsDialog
          hostId={hostId}
          volume={storedFor}
          inUse={volumes.find((v) => v.name === storedFor)?.inUse}
          volumeMissing={!volumes.some((v) => v.name === storedFor)}
          onDone={onDone}
          onClose={() => setStoredFor(null)}
        />
      )}
      {deleting && (
        <ConfirmDialog
          open
          danger
          onOpenChange={(open) => !open && setDeleting(null)}
          title={t('BackupSchedulesDialog.deleteScheduleForVolumenameQuestion', { volumeName: deleting.volumeName })}
          confirmLabel={t('BackupSchedulesDialog.delete')}
          loading={remove.isPending}
          onConfirm={() =>
            remove.mutate(deleting.id, {
              onSuccess: () => setDeleting(null),
              onError: (err) => {
                setDeleting(null)
                setError(describeBackupError(err))
              },
            })
          }
        >
          {t('BackupSchedulesDialog.theScheduleAndAllItsStoredBackups')}
        </ConfirmDialog>
      )}
    </Dialog>
  )
}
