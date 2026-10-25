import { Badge } from '../../shared/ui/index.ts'
import type { BackupStatus } from './backup.ts'

const dateFormat = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' })

/** Result of the last scheduled backup: status plus time; `–` before the first run. */
export function LastBackupBadge({ status, at }: { status: BackupStatus | null; at: string | null }) {
  if (!status) return <>–</>
  return (
    <span className="vbackup__badge">
      <Badge tone={status === 'SUCCESS' ? 'ok' : 'bad'}>{status === 'SUCCESS' ? 'Backup OK' : 'Backup failed'}</Badge>
      {at && <time dateTime={at}>{dateFormat.format(new Date(at))}</time>}
    </span>
  )
}
