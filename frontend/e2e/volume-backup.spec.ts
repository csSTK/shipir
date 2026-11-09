import { expect, test } from './fixtures/api.ts'

const local = {
  id: 'a',
  name: 'Local',
  connectionType: 'LOCAL',
  endpoint: 'unix:///var/run/docker.sock',
  status: 'UP',
  error: null,
  dockerVersion: '27.1.2',
  cpus: 8,
  memoryBytes: 16 * 1024 ** 3,
  containers: { running: 1, stopped: 0, healthy: 0, unhealthy: 0 },
  images: 0,
  volumes: 2,
}
const as = (role: string) => ({ body: { username: 'u', role, setupRequired: false } })
const volumes = [
  { name: 'pgdata', driver: 'local', mountpoint: '/v/pgdata', created: null, inUse: true },
  { name: 'cache', driver: 'local', mountpoint: '/v/cache', created: null, inUse: false },
]
const schedule = {
  id: 's1',
  volumeName: 'cache',
  intervalHours: 24,
  keepCount: 7,
  nextRunAt: '2026-10-26T10:00:00Z',
  lastRunAt: '2026-10-25T10:00:00Z',
  lastStatus: 'FAILED',
}

test.beforeEach(({ api }) => {
  api.mock('GET', '/api/hosts/overview', { body: [local] })
  api.mock('GET', '/api/hosts/a/volumes', { body: volumes })
})

test('operator sees the last result, restores into a volume in use and manages schedules', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', as('OPERATOR'))
  api.mock('GET', '/api/hosts/a/volume-backup-schedules', { body: [schedule] })
  api.mock('POST', '/api/hosts/a/volumes/pgdata/restore', { status: 204 })
  api.mock('DELETE', '/api/hosts/a/volume-backup-schedules/s1', { status: 204 })
  await page.goto('/environments/a/volumes')
  await expect(page.getByRole('row', { name: /cache/ }).getByText('Backup failed')).toBeVisible()

  await page.getByRole('button', { name: 'More actions for pgdata' }).click()
  await page.getByRole('menuitem', { name: 'Restore…' }).click()
  const dialog = page.getByRole('dialog', { name: 'Restore pgdata' })
  await expect(dialog).toContainText('100 MB')
  await dialog.getByLabel('Backup archive (.tar)').setInputFiles({ name: 'pgdata.tar', mimeType: 'application/x-tar', buffer: Buffer.from('tar') })
  const restore = dialog.getByRole('button', { name: 'Restore' })
  await expect(restore).toBeDisabled()
  await dialog.getByRole('switch').check()
  const request = page.waitForRequest((r) => r.method() === 'POST' && r.url().endsWith('/volumes/pgdata/restore'))
  await restore.click()
  expect((await request).postData()).toContain('true')
  await expect(page.getByRole('status')).toContainText('Restored pgdata.tar into pgdata.')

  await page.getByRole('button', { name: 'Backup schedules' }).click()
  const schedules = page.getByRole('dialog', { name: 'Backup schedules' })
  await expect(schedules.getByRole('listitem')).toContainText('every 24 h')
  const del = page.waitForRequest((r) => r.method() === 'DELETE' && r.url().endsWith('/volume-backup-schedules/s1'))
  await schedules.getByRole('button', { name: 'Delete schedule for cache' }).click()
  const confirm = page.getByRole('dialog', { name: 'Delete schedule for cache?' })
  await expect(confirm).toContainText('all its stored backups')
  await confirm.getByRole('button', { name: 'Delete' }).click()
  await del
})

test('operator downloads a backup of a volume in use', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', as('ADMIN'))
  api.mock('GET', '/api/hosts/a/volume-backup-schedules', { body: [] })
  await page.route('**/api/hosts/a/volumes/pgdata/backup', (route) =>
    route.fulfill({ status: 200, body: 'tar', headers: { 'Content-Type': 'application/x-tar', 'Content-Disposition': 'attachment; filename="pgdata-1.tar"' } }),
  )
  await page.goto('/environments/a/volumes')
  await page.getByRole('button', { name: 'More actions for pgdata' }).click()
  const download = page.waitForEvent('download')
  await page.getByRole('menuitem', { name: 'Backup', exact: true }).click()
  expect((await download).suggestedFilename()).toBe('pgdata-1.tar')
  await expect(page.getByRole('status')).toContainText('may be inconsistent')
})

test('operator lists and downloads a stored scheduled backup, and sees a failing download', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', as('OPERATOR'))
  api.mock('GET', '/api/hosts/a/volume-backup-schedules', { body: [] })
  api.mock('GET', '/api/hosts/a/volumes/cache/stored-backups', {
    body: [
      { name: 'cache-20260102T030405Z.tar', sizeBytes: 2048, createdAt: '2026-01-02T03:04:05Z' },
      { name: 'cache-20260101T000000Z.tar', sizeBytes: 12, createdAt: '2026-01-01T00:00:00Z' },
    ],
  })
  await page.route('**/api/hosts/a/volumes/cache/stored-backups/cache-20260102T030405Z.tar', (route) =>
    route.fulfill({ status: 200, body: 'tar', headers: { 'Content-Type': 'application/x-tar', 'Content-Disposition': 'attachment; filename="cache-20260102T030405Z.tar"' } }),
  )
  await page.route('**/api/hosts/a/volumes/cache/stored-backups/cache-20260101T000000Z.tar', (route) =>
    route.fulfill({ status: 404, json: { code: 'STORED_BACKUP_NOT_FOUND' } }),
  )
  await page.goto('/environments/a/volumes')
  await page.getByRole('button', { name: 'More actions for cache' }).click()
  await page.getByRole('menuitem', { name: 'Stored backups…' }).click()
  const dialog = page.getByRole('dialog', { name: 'Stored backups of cache' })
  await expect(dialog.getByRole('listitem')).toHaveCount(2)
  await expect(dialog.getByRole('listitem').first()).toContainText('2 KiB')

  const download = page.waitForEvent('download')
  await dialog.getByRole('button', { name: 'Download cache-20260102T030405Z.tar' }).click()
  expect((await download).suggestedFilename()).toBe('cache-20260102T030405Z.tar')

  await dialog.getByRole('button', { name: 'Download cache-20260101T000000Z.tar' }).click()
  await expect(dialog.getByRole('alert')).toContainText('This stored backup no longer exists.')
})

test('operator restores a stored scheduled backup into a volume in use only after confirming, and sees a rejected archive', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', as('OPERATOR'))
  api.mock('GET', '/api/hosts/a/volume-backup-schedules', { body: [] })
  api.mock('GET', '/api/hosts/a/volumes/pgdata/stored-backups', {
    body: [
      { name: 'pgdata-20260102T030405Z.tar', sizeBytes: 2048, createdAt: '2026-01-02T03:04:05Z' },
      { name: 'pgdata-20260101T000000Z.tar', sizeBytes: 12, createdAt: '2026-01-01T00:00:00Z' },
    ],
  })
  const calls: string[] = []
  await page.route('**/api/hosts/a/volumes/pgdata/stored-backups/*/restore?*', (route) => {
    const url = route.request().url()
    calls.push(url)
    if (url.includes('20260101T000000Z')) return route.fulfill({ status: 400, json: { code: 'INVALID_ARCHIVE' } })
    return route.fulfill({ status: 204 })
  })
  await page.goto('/environments/a/volumes')
  await page.getByRole('button', { name: 'More actions for pgdata' }).click()
  await page.getByRole('menuitem', { name: 'Stored backups…' }).click()
  const dialog = page.getByRole('dialog', { name: 'Stored backups of pgdata' })

  await dialog.getByRole('button', { name: 'Restore pgdata-20260101T000000Z.tar' }).click()
  await expect(dialog).toContainText('overlaid onto the existing volume')
  await expect(dialog.getByRole('button', { name: 'Restore', exact: true })).toBeDisabled()
  await dialog.getByRole('switch').check()
  await dialog.getByRole('button', { name: 'Restore', exact: true }).click()
  await expect(dialog.getByRole('alert')).toContainText('not a valid backup archive')

  await dialog.getByRole('button', { name: 'Restore pgdata-20260102T030405Z.tar' }).click()
  const restore = dialog.getByRole('button', { name: 'Restore', exact: true })
  await expect(restore).toBeDisabled()
  await dialog.getByRole('switch').check()
  await restore.click()
  await expect(page.getByRole('status')).toContainText('Restored pgdata-20260102T030405Z.tar into pgdata.')
  expect(calls.at(-1)).toContain('confirm=true')
})

test('viewer gets no backup actions', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', as('VIEWER'))
  await page.goto('/environments/a/volumes')
  await expect(page.getByText('pgdata')).toBeVisible()
  await expect(page.getByRole('button', { name: /More actions/ })).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Backup schedules' })).toHaveCount(0)
})

test('operator opens the stored backups of a schedule whose volume is gone from Docker', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', as('OPERATOR'))
  api.mock('GET', '/api/hosts/a/volume-backup-schedules', { body: [{ ...schedule, id: 's2', volumeName: 'gone', lastStatus: 'SUCCESS' }] })
  api.mock('GET', '/api/hosts/a/volumes/gone/stored-backups', {
    body: [{ name: 'gone-20260101T000000Z.tar', sizeBytes: 12, createdAt: '2026-01-01T00:00:00Z' }],
  })
  await page.route('**/api/hosts/a/volumes/gone/stored-backups/gone-20260101T000000Z.tar', (route) =>
    route.fulfill({ status: 200, body: 'tar', headers: { 'Content-Type': 'application/x-tar', 'Content-Disposition': 'attachment; filename="gone-20260101T000000Z.tar"' } }),
  )
  await page.goto('/environments/a/volumes')
  await page.getByRole('button', { name: 'Backup schedules' }).click()
  await page.getByRole('dialog', { name: 'Backup schedules' }).getByRole('button', { name: 'Stored backups of gone' }).click()
  const dialog = page.getByRole('dialog', { name: 'Stored backups of gone' })
  await expect(dialog.getByRole('listitem')).toHaveCount(1)
  const download = page.waitForEvent('download')
  await dialog.getByRole('button', { name: 'Download gone-20260101T000000Z.tar' }).click()
  expect((await download).suggestedFilename()).toBe('gone-20260101T000000Z.tar')
  await dialog.getByRole('button', { name: 'Restore gone-20260101T000000Z.tar' }).click()
  await expect(dialog.getByText('Create the volume first, then restore.')).toBeVisible()
  await expect(dialog.getByRole('button', { name: 'Restore', exact: true })).toBeDisabled()
})
