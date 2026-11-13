# Code map: frontend volumes

- frontend/src/features/volumes/{api.ts,index.ts,VolumesPage.tsx,VolumesPage.css,VolumesPage.test.tsx}: `/environments/:id/volumes` table with usage badges, row menu and list hook
- frontend/src/features/volumes/CreateVolumeDialog.tsx: create volume dialog
- frontend/src/features/volumes/{backup.ts,dateFormat.ts,BackupSchedulesDialog.tsx,RestoreVolumeDialog.tsx,StoredBackupsDialog.tsx,LastBackupBadge.tsx,VolumeBackup.css}: volume backup UI: download, restore, stored backups, schedules
- frontend/src/features/volumes/{VolumeBackup.test.tsx,StoredBackups.test.tsx}: volume backup, restore and stored backups tests
