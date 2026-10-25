import { Trash2 } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { Button, Dialog, IconButton, Select, TextField } from '../../shared/ui/index.ts'
import type { Volume } from './api.ts'
import { describeBackupError, useBackupSchedules, useCreateSchedule, useDeleteSchedule } from './backup.ts'
import { LastBackupBadge } from './LastBackupBadge.tsx'
import './VolumeBackup.css'

interface Props {
  hostId: string
  volumes: Volume[]
  initialVolume?: string
  onClose: () => void
}

const rangeError = (raw: string, max: number) => {
  const n = Number(raw)
  return Number.isInteger(n) && n >= 1 && n <= max ? null : `Enter 1 to ${max} hours.`
}

/** Lists the environment's backup schedules and adds or removes them. */
export function BackupSchedulesDialog({ hostId, volumes, initialVolume, onClose }: Props) {
  const schedules = useBackupSchedules(hostId)
  const create = useCreateSchedule(hostId)
  const remove = useDeleteSchedule(hostId)
  const [volume, setVolume] = useState(initialVolume ?? volumes[0]?.name ?? '')
  const [interval, setIntervalHours] = useState('24')
  const [keep, setKeep] = useState('7')
  const [touched, setTouched] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const intervalError = rangeError(interval, 8760)
  const keepError = Number.isInteger(Number(keep)) && Number(keep) >= 1 && Number(keep) <= 100 ? null : 'Enter 1 to 100 backups.'
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
    <Dialog open onOpenChange={(open) => !open && onClose()} title="Backup schedules" description="Automatic backups are stored on the Shipir server; the newest ones are kept.">
      <div className="vbackup__stack">
        {schedules.isPending ? (
          <p className="ui-hint">Loading…</p>
        ) : schedules.error ? (
          <p role="alert" className="ui-error">
            {describeBackupError(schedules.error)}
          </p>
        ) : schedules.data.length === 0 ? (
          <p className="ui-hint">No schedules yet.</p>
        ) : (
          <ul className="vbackup__list">
            {schedules.data.map((s) => (
              <li key={s.id} className="vbackup__item">
                <div className="vbackup__item-text">
                  <strong>{s.volumeName}</strong>
                  <span className="ui-hint">
                    every {s.intervalHours} h, keep {s.keepCount}
                  </span>
                </div>
                <LastBackupBadge status={s.lastStatus} at={s.lastRunAt} />
                <IconButton
                  icon={Trash2}
                  variant="danger"
                  aria-label={`Delete schedule for ${s.volumeName}`}
                  onClick={() => remove.mutate(s.id, { onError: (err) => setError(describeBackupError(err)) })}
                />
              </li>
            ))}
          </ul>
        )}

        <form className="ui-form" onSubmit={submit} noValidate>
          <Select label="Volume" value={volume} onChange={(e) => setVolume(e.target.value)}>
            {volumes.map((v) => (
              <option key={v.name} value={v.name}>
                {v.name}
              </option>
            ))}
          </Select>
          {selected?.inUse && <p className="ui-hint">This volume is in use; a backup taken while it is written to may be inconsistent.</p>}
          <div className="vbackup__row">
            <TextField label="Interval (hours)" type="number" min={1} max={8760} value={interval} onChange={(e) => setIntervalHours(e.target.value)} error={touched ? intervalError : null} />
            <TextField label="Keep newest" type="number" min={1} max={100} value={keep} onChange={(e) => setKeep(e.target.value)} error={touched ? keepError : null} />
          </div>
          {error && (
            <p role="alert" className="ui-error">
              {error}
            </p>
          )}
          <div className="ui-dialog-actions">
            <Button variant="ghost" onClick={onClose}>
              Close
            </Button>
            <Button variant="primary" type="submit" loading={create.isPending}>
              Add schedule
            </Button>
          </div>
        </form>
      </div>
    </Dialog>
  )
}
