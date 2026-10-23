import { expect, test } from './fixtures/api.ts'

const admin = { username: 'admin', role: 'ADMIN', setupRequired: false, setupStep: null }
const operator = { username: 'op', role: 'OPERATOR', setupRequired: false, setupStep: null }

const entry = (id: string, action: string, over: Record<string, unknown> = {}) => ({
  id,
  occurredAt: '2026-10-01T10:15:00Z',
  username: 'admin',
  action,
  outcome: 'SUCCESS',
  targetType: null,
  targetId: null,
  details: null,
  ...over,
})

test('admin filters by action and pages', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', { body: admin })
  const requests: string[] = []
  await page.route((u) => u.pathname === '/api/audit-log', async (route) => {
    const url = new URL(route.request().url())
    requests.push(url.search)
    const filtered = url.searchParams.get('action') === 'LOGIN'
    const pageNo = Number(url.searchParams.get('page'))
    const body = filtered
      ? { items: [entry('l1', 'LOGIN', { outcome: 'FAILURE', username: 'mallory' })], page: 0, size: 20, totalElements: 1 }
      : { items: [entry(`h${pageNo}`, pageNo === 0 ? 'HOST_CREATE' : 'HOST_DELETE')], page: pageNo, size: 20, totalElements: 25 }
    await route.fulfill({ json: body })
  })
  await page.goto('/settings/audit-log')

  await expect(page.getByRole('cell', { name: 'Host created' })).toBeVisible()
  await page.getByRole('button', { name: 'Next' }).click()
  await expect(page.getByRole('cell', { name: 'Host deleted' })).toBeVisible()
  await expect(page).toHaveURL(/page=2/)

  await page.getByLabel('Action').selectOption('LOGIN')
  await page.getByRole('button', { name: 'Apply' }).click()
  await expect(page.getByText('mallory')).toBeVisible()
  await expect(page.getByText('Failure')).toBeVisible()
  await expect(page).toHaveURL(/action=LOGIN/)
  expect(requests.some((r) => r.includes('action=LOGIN'))).toBe(true)
})

test('operator cannot reach the audit log', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', { body: operator })
  await page.goto('/settings/audit-log')
  await expect(page.getByRole('heading', { name: 'Audit log' })).toHaveCount(0)
  await expect(page.getByText('You are not allowed to view this page.')).toBeVisible()
})
