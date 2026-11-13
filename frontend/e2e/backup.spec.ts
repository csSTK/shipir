import { expect, test } from './fixtures/api.ts'

const admin = { username: 'admin', role: 'ADMIN', setupRequired: false, setupStep: null }
const operator = { username: 'ops', role: 'OPERATOR', setupRequired: false, setupStep: null }
const PASSPHRASE = 'correct horse battery'
const PASSWORD = 'My-current-Passw0rd!'
const passwordUser = { method: 'PASSWORD', totpRequired: false, fresh: false, windowSeconds: 300 }

/** Answers the "Confirm it's you" dialog that opens once the passphrase is valid. */
async function confirmIdentity(page: import('@playwright/test').Page, password = PASSWORD) {
  const dialog = page.getByRole('dialog', { name: "Confirm it's you" })
  await dialog.getByLabel('Current password').fill(password)
  await dialog.getByRole('button', { name: 'Confirm' }).click()
  return dialog
}

test('admin downloads an encrypted backup and the fields are cleared', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', { body: admin })
  api.mock('GET', '/api/auth/step-up', { body: passwordUser })
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
  await confirmIdentity(page)

  expect((await download).suggestedFilename()).toBe('shipir-backup-20261024-120000.tar.gz.enc')
  expect(sent).toEqual({ passphrase: PASSPHRASE, stepUp: { password: PASSWORD } })
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
  api.mock('GET', '/api/auth/step-up', { body: passwordUser })
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
  await confirmIdentity(page)

  await expect(page.getByRole('alert')).toContainText('passphrase: size must be between 12 and 128')
  await expect(page.getByRole('dialog')).toHaveCount(0)
  await expect(page.getByText('Backup downloaded.')).toHaveCount(0)
})

test('a wrong password keeps the confirmation open, then the right one downloads', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', { body: admin })
  api.mock('GET', '/api/auth/step-up', { body: passwordUser })
  const attempts: unknown[] = []
  await page.route('**/api/backup', async (route) => {
    const body = route.request().postDataJSON() as { stepUp: { password: string } }
    attempts.push(body.stepUp.password)
    if (body.stepUp.password !== PASSWORD) {
      await route.fulfill({
        status: 403,
        contentType: 'application/json',
        body: JSON.stringify({ code: 'STEP_UP_INVALID', message: 'The password or code is not correct' }),
      })
      return
    }
    await route.fulfill({
      status: 200,
      contentType: 'application/octet-stream',
      headers: { 'Content-Disposition': 'attachment; filename="shipir-backup.tar.gz.enc"' },
      body: Buffer.from('encrypted-bytes'),
    })
  })
  await page.goto('/settings/backup')
  await page.getByLabel('Passphrase', { exact: true }).fill(PASSPHRASE)
  await page.getByLabel('Confirm passphrase').fill(PASSPHRASE)
  await page.getByRole('button', { name: 'Download backup' }).click()

  const dialog = await confirmIdentity(page, 'not-my-password')
  await expect(dialog.getByRole('alert')).toContainText('password or code is not correct')
  await expect(dialog.getByLabel('Current password')).toHaveValue('')

  const download = page.waitForEvent('download')
  await dialog.getByLabel('Current password').fill(PASSWORD)
  await dialog.getByRole('button', { name: 'Confirm' }).click()
  expect((await download).suggestedFilename()).toBe('shipir-backup.tar.gz.enc')
  expect(attempts).toEqual(['not-my-password', PASSWORD])
})

test('cancelling the confirmation sends no backup request', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', { body: admin })
  api.mock('GET', '/api/auth/step-up', { body: passwordUser })
  let requested = false
  await page.route('**/api/backup', (route) => {
    requested = true
    return route.abort()
  })
  await page.goto('/settings/backup')
  await page.getByLabel('Passphrase', { exact: true }).fill(PASSPHRASE)
  await page.getByLabel('Confirm passphrase').fill(PASSPHRASE)
  await page.getByRole('button', { name: 'Download backup' }).click()

  const dialog = page.getByRole('dialog', { name: "Confirm it's you" })
  await dialog.getByRole('button', { name: 'Cancel' }).click()

  await expect(page.getByRole('dialog')).toHaveCount(0)
  expect(requested).toBe(false)
  await expect(page.getByLabel('Passphrase', { exact: true })).toHaveValue(PASSPHRASE)
})

test('operator is not allowed to open the backup page', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', { body: operator })
  await page.goto('/settings/backup')

  await expect(page.getByRole('alert')).toContainText('not allowed')
  await expect(page.getByLabel('Passphrase', { exact: true })).toHaveCount(0)
})
