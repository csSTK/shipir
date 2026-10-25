import { useMemo, useState } from 'react'
import { useCurrentUser } from '../auth/index.ts'
import { deleteMenu } from '../hosts/deleteRowActions.tsx'
import { DeleteResourceDialog } from '../hosts/DeleteResourceDialog.tsx'
import { useParams } from 'react-router-dom'
import { describeResourceError } from '../hosts/format.ts'
import { Download, Upload, CalendarClock } from 'lucide-react'
import { Badge, Button, PageHeader, ResourceTable, type ResourceColumn } from '../../shared/ui/index.ts'
import { useDeleteVolume, useVolumes, type Volume } from './api.ts'
import { CreateVolumeDialog } from './CreateVolumeDialog.tsx'
import { BackupSchedulesDialog } from './BackupSchedulesDialog.tsx'
import { RestoreVolumeDialog } from './RestoreVolumeDialog.tsx'
import { LastBackupBadge } from './LastBackupBadge.tsx'
import { describeBackupError, downloadVolumeBackup, useBackupSchedules } from './backup.ts'
import './VolumesPage.css'
import './VolumeBackup.css'

const dateFormat = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' })

const baseColumns: ResourceColumn<Volume>[] = [
  { id: 'name', header: 'Name', sortValue: (v) => v.name, render: (v) => <strong>{v.name}</strong> },
  { id: 'driver', header: 'Driver', sortValue: (v) => v.driver, render: (v) => v.driver },
  {
    id: 'created',
    header: 'Created',
    sortValue: (v) => (v.created ? Date.parse(v.created) : null),
    render: (v) => (v.created ? dateFormat.format(new Date(v.created)) : '–'),
  },
  {
    id: 'usage',
    header: 'Usage',
    sortValue: (v) => (v.inUse ? 1 : 0),
    render: (v) => <Badge tone={v.inUse ? 'ok' : 'muted'}>{v.inUse ? 'In use' : 'Unused'}</Badge>,
  },
]

/** `/environments/:id/volumes`: all volumes of one environment. */
export function VolumesPage() {
  const { id = '' } = useParams()
  const { data: user } = useCurrentUser()
  const canWrite = user?.role === 'ADMIN' || user?.role === 'OPERATOR'
  const { data, isPending, error, refetch } = useVolumes(id)
  const schedules = useBackupSchedules(id, canWrite)
  const del = useDeleteVolume(id)
  const [target, setTarget] = useState<Volume | null>(null)
  const [creating, setCreating] = useState(false)
  const [restoring, setRestoring] = useState<Volume | null>(null)
  const [scheduling, setScheduling] = useState<{ volume?: string } | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [backupError, setBackupError] = useState<string | null>(null)

  const columns = useMemo<ResourceColumn<Volume>[]>(() => {
    if (!canWrite) return baseColumns
    const lastRun = new Map((schedules.data ?? []).map((s) => [s.volumeName, s]))
    return [
      ...baseColumns,
      {
        id: 'backup',
        header: 'Last backup',
        sortValue: (v) => {
          const at = lastRun.get(v.name)?.lastRunAt
          return at ? Date.parse(at) : null
        },
        render: (v) => <LastBackupBadge status={lastRun.get(v.name)?.lastStatus ?? null} at={lastRun.get(v.name)?.lastRunAt ?? null} />,
      },
    ]
  }, [canWrite, schedules.data])

  async function backup(v: Volume) {
    setNotice(null)
    setBackupError(null)
    try {
      await downloadVolumeBackup(id, v.name)
      setNotice(
        v.inUse
          ? `Backup of ${v.name} downloaded. ${v.name} is in use, so the archive may be inconsistent.`
          : `Backup of ${v.name} downloaded.`,
      )
    } catch (e) {
      setBackupError(describeBackupError(e))
    }
  }

  return (
    <section className="volumes">
      <PageHeader
        title="Volumes"
        actions={
          canWrite && (
            <>
              <Button variant="secondary" onClick={() => setScheduling({})}>
                Backup schedules
              </Button>
              <Button variant="primary" onClick={() => setCreating(true)}>
                Create volume
              </Button>
            </>
          )
        }
      />
      {notice && (
        <p role="status" className="vbackup__status">
          {notice}
        </p>
      )}
      {backupError && (
        <p role="alert" className="ui-error">
          {backupError}
        </p>
      )}
      <ResourceTable
        label="volumes"
        columns={columns}
        rows={data}
        rowKey={(v) => v.name}
        searchText={(v) => `${v.name} ${v.driver}`}
        defaultSort="name"
        emptyMessage="No volumes in this environment."
        isPending={isPending}
        error={error ? describeResourceError(error) : null}
        onRetry={() => void refetch()}
        rowLabel={(v) => v.name}
        rowActions={
          canWrite
            ? (v) => ({
                menu: [
                  { label: 'Backup', icon: <Download size={16} strokeWidth={1.75} />, onSelect: () => void backup(v) },
                  { label: 'Restore…', icon: <Upload size={16} strokeWidth={1.75} />, onSelect: () => setRestoring(v) },
                  { label: 'Schedule backups…', icon: <CalendarClock size={16} strokeWidth={1.75} />, onSelect: () => setScheduling({ volume: v.name }) },
                  { type: 'separator' },
                  ...deleteMenu(() => setTarget(v)),
                ],
              })
            : undefined
        }
      />
      {restoring && <RestoreVolumeDialog hostId={id} volume={restoring} onClose={() => setRestoring(null)} onDone={setNotice} />}
      {scheduling && <BackupSchedulesDialog hostId={id} volumes={data ?? []} initialVolume={scheduling.volume} onClose={() => setScheduling(null)} />}
      {creating && <CreateVolumeDialog hostId={id} onClose={() => setCreating(false)} />}
      {target && (
        <DeleteResourceDialog kind="volume" name={target.name} onDelete={() => del.mutateAsync(target.name)} onClose={() => setTarget(null)} />
      )}
    </section>
  )
}
