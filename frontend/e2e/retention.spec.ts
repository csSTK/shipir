import { expect, test } from './fixtures/api.ts'

const admin = { username: 'admin', role: 'ADMIN', setupRequired: false, setupStep: null }

test('admin changes the retention periods and saves', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', { body: admin })
  api.mock('GET', '/api/settings/retention', { body: { auditLogDays: 90, notificationDays: 30 } })
  api.mock('PUT', '/api/settings/retention', { body: { auditLogDays: 365, notificationDays: 14 } })
  await page.goto('/settings/retention')

  const audit = page.getByLabel('Audit log retention (days)')
  await expect(audit).toHaveValue('90')
  await audit.fill('365')
  await page.getByLabel('Notification retention (days)').fill('14')
  await page.getByRole('button', { name: 'Save' }).click()

  await expect(page.getByText('Retention settings saved.')).toBeVisible()
  await expect(audit).toHaveValue('365')
})

test('server rejection is shown inline and nothing is reported as saved', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', { body: admin })
  api.mock('GET', '/api/settings/retention', { body: { auditLogDays: 90, notificationDays: 30 } })
  api.mock('PUT', '/api/settings/retention', {
    status: 400,
    body: { code: 'VALIDATION_FAILED', message: 'Invalid', details: ['notificationDays must be at least 1'] },
  })
  await page.goto('/settings/retention')

  await page.getByRole('button', { name: 'Save' }).click()
  await expect(page.getByRole('alert')).toContainText('notificationDays must be at least 1')
  await expect(page.getByText('Retention settings saved.')).toHaveCount(0)
})
