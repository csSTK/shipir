import { expect, test } from './fixtures/api.ts'

const admin = { username: 'admin', role: 'ADMIN', setupRequired: false, setupStep: null }
const operator = { username: 'ops', role: 'OPERATOR', setupRequired: false, setupStep: null }
const PASSPHRASE = 'correct horse battery'

test('admin downloads an encrypted backup and the fields are cleared', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', { body: admin })
  let sent: unknown
  // Registered after the api fixture, so this route answers first with the binary file.
  await page.route('**/api/backup', async (route) => {
    sent = route.request().postDataJSON()
    await route.fulfill({
      status: 200,
      contentType: 'application/octet-stream',
      headers: { 'Content-Disposition': 'attachment; filename="shipir-backup-20261024-120000.tar.gz.enc"' },
      body: Buffer.from('encrypted-bytes'),
    })
  })
  await page.goto('/settings/backup')

  await page.getByLabel('Passphrase', { exact: true }).fill(PASSPHRASE)
  await page.getByLabel('Confirm passphrase').fill(PASSPHRASE)
  const download = page.waitForEvent('download')
  await page.getByRole('button', { name: 'Download backup' }).click()

  expect((await download).suggestedFilename()).toBe('shipir-backup-20261024-120000.tar.gz.enc')
  expect(sent).toEqual({ passphrase: PASSPHRASE })
  await expect(page.getByText('Backup downloaded.')).toBeVisible()
  await expect(page.getByLabel('Passphrase', { exact: true })).toHaveValue('')
})

test('mismatched confirmation is refused inline without a request', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', { body: admin })
  await page.goto('/settings/backup')

  await page.getByLabel('Passphrase', { exact: true }).fill(PASSPHRASE)
  await page.getByLabel('Confirm passphrase').fill('something else entirely')
  await page.getByRole('button', { name: 'Download backup' }).click()

  await expect(page.getByRole('alert')).toContainText('do not match')
})

test('server rejection is shown inline and no download starts', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', { body: admin })
  await page.route('**/api/backup', (route) =>
    route.fulfill({
      status: 400,
      contentType: 'application/json',
      body: JSON.stringify({ code: 'VALIDATION_ERROR', message: 'Invalid', details: ['passphrase: size must be between 12 and 128'] }),
    }),
  )
  await page.goto('/settings/backup')

  await page.getByLabel('Passphrase', { exact: true }).fill(PASSPHRASE)
  await page.getByLabel('Confirm passphrase').fill(PASSPHRASE)
  await page.getByRole('button', { name: 'Download backup' }).click()

  await expect(page.getByRole('alert')).toContainText('passphrase: size must be between 12 and 128')
  await expect(page.getByText('Backup downloaded.')).toHaveCount(0)
})

test('operator is not allowed to open the backup page', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', { body: operator })
  await page.goto('/settings/backup')

  await expect(page.getByRole('alert')).toContainText('not allowed')
  await expect(page.getByLabel('Passphrase', { exact: true })).toHaveCount(0)
})
