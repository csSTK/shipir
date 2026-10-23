import { expect, test } from './fixtures/api.ts'

const admin = { username: 'admin', role: 'ADMIN', setupRequired: false, setupStep: null }
const operator = { username: 'op', role: 'OPERATOR', setupRequired: false, setupStep: null }

const session = {
  id: 's1',
  title: 'Why is web down',
  sharedWithAdmin: true,
  createdAt: '2026-10-22T09:00:00Z',
  updatedAt: '2026-10-22T10:00:00Z',
  ownerUsername: 'bob',
}

const transcript = {
  session,
  messages: [
    { id: 'm1', seq: 1, role: 'USER', content: 'web returns 502', toolCalls: null, toolCallId: null, status: null, createdAt: '2026-10-22T09:00:00Z' },
    {
      id: 'm2',
      seq: 2,
      role: 'TOOL',
      content: null,
      toolCalls: [{ id: 'c1', name: 'restart_container', arguments: { name: 'web' }, description: 'Restart web' }],
      toolCallId: null,
      status: 'PENDING',
      createdAt: '2026-10-22T09:01:00Z',
    },
  ],
}

test('admin opens a shared session read-only', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', { body: admin })
  api.mock('GET', '/api/ai/shared-sessions', { body: { items: [session], page: 0, size: 20, totalElements: 1 } })
  api.mock('GET', '/api/ai/shared-sessions/s1', { body: transcript })
  await page.goto('/settings/ai/shared')

  await page.getByRole('button', { name: /Why is web down/ }).click()
  await expect(page).toHaveURL(/session=s1/)
  await expect(page.getByText('web returns 502')).toBeVisible()
  await expect(page.getByRole('region', { name: /restart container/ })).toBeVisible()
  await expect(page.getByRole('button', { name: /confirm|reject/i })).toHaveCount(0)
})

test('admin sees an error when the shared chat cannot be loaded', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', { body: admin })
  api.mock('GET', '/api/ai/shared-sessions', { body: { items: [session], page: 0, size: 20, totalElements: 1 } })
  api.mock('GET', '/api/ai/shared-sessions/s1', { status: 404, body: { code: 'NOT_FOUND', message: 'gone' } })
  await page.goto('/settings/ai/shared')

  await page.getByRole('button', { name: /Why is web down/ }).click()
  await expect(page.getByRole('alert')).toHaveText(/Could not load this chat/)
})

test('operator cannot reach shared chats', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', { body: operator })
  await page.goto('/settings/ai/shared')
  await expect(page.getByRole('heading', { name: 'Shared chats' })).toHaveCount(0)
  await expect(page.getByText('You are not allowed to view this page.')).toBeVisible()
})
