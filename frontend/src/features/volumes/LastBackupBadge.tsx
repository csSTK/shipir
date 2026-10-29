import { Badge } from '../../shared/ui/index.ts'
import type { BackupStatus } from './backup.ts'
import { useTranslation } from 'react-i18next'
import { dateFormat } from './dateFormat.ts'

/** Result of the last scheduled backup: status plus time; `–` before the first run. */
export function LastBackupBadge({ status, at }: { status: BackupStatus | null; at: string | null }) {
  const { t } = useTranslation('volumes')
  if (!status) return <>–</>
  return (
    <span className="vbackup__badge">
      <Badge tone={status === 'SUCCESS' ? 'ok' : 'bad'}>{status === 'SUCCESS' ? t('LastBackupBadge.backupOk') : t('LastBackupBadge.backupFailed')}</Badge>
      {at && <time dateTime={at}>{dateFormat.format(new Date(at))}</time>}
    </span>
  )
}
