import { useMemo, useState } from 'react'
import { useCurrentUser } from '../auth/index.ts'
import { deleteMenu } from '../hosts/deleteRowActions.tsx'
import { DeleteResourceDialog } from '../hosts/DeleteResourceDialog.tsx'
import { useParams } from 'react-router-dom'
import { describeResourceError } from '../hosts/format.ts'
import { Archive, Download, Upload, CalendarClock } from 'lucide-react'
import { Badge, Button, PageHeader, ResourceTable, type ResourceColumn } from '../../shared/ui/index.ts'
import { useDeleteVolume, useVolumes, type Volume } from './api.ts'
import { CreateVolumeDialog } from './CreateVolumeDialog.tsx'
import { BackupSchedulesDialog } from './BackupSchedulesDialog.tsx'
import { StoredBackupsDialog } from './StoredBackupsDialog.tsx'
import { RestoreVolumeDialog } from './RestoreVolumeDialog.tsx'
import { LastBackupBadge } from './LastBackupBadge.tsx'
import { describeBackupError, downloadVolumeBackup, useBackupSchedules } from './backup.ts'
import { useTranslation } from 'react-i18next'
import i18n from 'i18next'
import { dateFormat } from './dateFormat.ts'
import './VolumesPage.css'
import './VolumeBackup.css'

const baseColumns: ResourceColumn<Volume>[] = [
  { id: 'name', get header() { return i18n.t('volumes:VolumesPage.name') }, sortValue: (v) => v.name, render: (v) => <strong>{v.name}</strong> },
  { id: 'driver', get header() { return i18n.t('volumes:VolumesPage.driver') }, sortValue: (v) => v.driver, render: (v) => v.driver },
  {
    id: 'created',
    get header() { return i18n.t('volumes:VolumesPage.created') },
    sortValue: (v) => (v.created ? Date.parse(v.created) : null),
    render: (v) => (v.created ? dateFormat.format(new Date(v.created)) : '–'),
  },
  {
    id: 'usage',
    get header() { return i18n.t('volumes:VolumesPage.usage') },
    sortValue: (v) => (v.inUse ? 1 : 0),
    render: (v) => <Badge tone={v.inUse ? 'ok' : 'muted'}>{v.inUse ? i18n.t('volumes:VolumesPage.inUse') : i18n.t('volumes:VolumesPage.unused')}</Badge>,
  },
]

/** `/environments/:id/volumes`: all volumes of one environment. */
export function VolumesPage() {
  const { t } = useTranslation('volumes')
  const { id = '' } = useParams()
  const { data: user } = useCurrentUser()
  const canWrite = user?.role === 'ADMIN' || user?.role === 'OPERATOR'
  const { data, isPending, error, refetch } = useVolumes(id)
  const schedules = useBackupSchedules(id, canWrite)
  const del = useDeleteVolume(id)
  const [target, setTarget] = useState<Volume | null>(null)
  const [creating, setCreating] = useState(false)
  const [restoring, setRestoring] = useState<Volume | null>(null)
  const [storedFor, setStoredFor] = useState<string | null>(null)
  const [scheduling, setScheduling] = useState<{ volume?: string } | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [backingUp, setBackingUp] = useState(false)
  const [backupError, setBackupError] = useState<string | null>(null)

  const columns = useMemo<ResourceColumn<Volume>[]>(() => {
    if (!canWrite) return baseColumns
    const lastRun = new Map((schedules.data ?? []).map((s) => [s.volumeName, s]))
    return [
      ...baseColumns,
      {
        id: 'backup',
        header: t('VolumesPage.lastBackup'),
        sortValue: (v) => {
          const at = lastRun.get(v.name)?.lastRunAt
          return at ? Date.parse(at) : null
        },
        render: (v) => <LastBackupBadge status={lastRun.get(v.name)?.lastStatus ?? null} at={lastRun.get(v.name)?.lastRunAt ?? null} />,
      },
    ]
  }, [canWrite, schedules.data, t])

  async function backup(v: Volume) {
    if (backingUp) return
    setBackingUp(true)
    setNotice(null)
    setBackupError(null)
    try {
      await downloadVolumeBackup(id, v.name)
      setNotice(
        v.inUse
          ? t('VolumesPage.backupOfNameDownloadedName2', { name: v.name, volume: v.name })
          : t('VolumesPage.backupOfNameDownloaded', { name: v.name }),
      )
    } catch (e) {
      setBackupError(describeBackupError(e))
    } finally {
      setBackingUp(false)
    }
  }

  return (
    <section className="ui-page volumes">
      <PageHeader
        title={t('VolumesPage.volumes')}
        actions={
          canWrite && (
            <>
              <Button variant="secondary" onClick={() => setScheduling({})}>
                {t('VolumesPage.backupSchedules')}
              </Button>
              <Button variant="primary" onClick={() => setCreating(true)}>
                {t('VolumesPage.createVolume')}
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
        label={t('VolumesPage.volumes2')}
        columns={columns}
        rows={data}
        rowKey={(v) => v.name}
        searchText={(v) => `${v.name} ${v.driver}`}
        defaultSort="name"
        emptyMessage={t('VolumesPage.noVolumesInThisEnvironment')}
        isPending={isPending}
        error={error ? describeResourceError(error) : null}
        onRetry={() => void refetch()}
        rowLabel={(v) => v.name}
        rowActions={
          canWrite
            ? (v) => ({
                menu: [
                  { label: t('VolumesPage.backup'), icon: <Download size={16} strokeWidth={1.75} />, onSelect: () => void backup(v), disabled: backingUp },
                  { label: t('VolumesPage.restore'), icon: <Upload size={16} strokeWidth={1.75} />, onSelect: () => setRestoring(v) },
                  { label: t('VolumesPage.scheduleBackups'), icon: <CalendarClock size={16} strokeWidth={1.75} />, onSelect: () => setScheduling({ volume: v.name }) },
                  { label: t('VolumesPage.storedBackups'), icon: <Archive size={16} strokeWidth={1.75} />, onSelect: () => setStoredFor(v.name) },
                  { type: 'separator' },
                  ...deleteMenu(() => setTarget(v)),
                ],
              })
            : undefined
        }
      />
      {restoring && <RestoreVolumeDialog hostId={id} volume={restoring} onClose={() => setRestoring(null)} onDone={setNotice} />}
      {scheduling && <BackupSchedulesDialog hostId={id} volumes={data ?? []} initialVolume={scheduling.volume} onDone={setNotice} onClose={() => setScheduling(null)} />}
      {storedFor && (
        <StoredBackupsDialog
          hostId={id}
          volume={storedFor}
          inUse={data?.find((v) => v.name === storedFor)?.inUse}
          onDone={setNotice}
          onClose={() => setStoredFor(null)}
        />
      )}
      {creating && <CreateVolumeDialog hostId={id} onClose={() => setCreating(false)} />}
      {target && (
        <DeleteResourceDialog kind="volume" name={target.name} onDelete={() => del.mutateAsync(target.name)} onClose={() => setTarget(null)} />
      )}
    </section>
  )
}
