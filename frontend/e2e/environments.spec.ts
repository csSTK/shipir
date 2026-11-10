import type { Page } from '@playwright/test'
import { expect, test } from './fixtures/api.ts'

async function chooseAction(page: Page, env: string, item: 'Rename' | 'Delete') {
  await page.getByRole('button', { name: `Actions for ${env}` }).click()
  await page.getByRole('menuitem', { name: item }).click()
}

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
  containers: { running: 5, stopped: 2, healthy: 3, unhealthy: 1 },
  images: 12,
  volumes: 4,
}
const staging = {
  id: 'b',
  name: 'Staging',
  connectionType: 'TCP',
  endpoint: 'tcp://staging.example:2376',
  status: 'DOWN',
  error: 'TIMEOUT',
  dockerVersion: null,
  cpus: null,
  memoryBytes: null,
  containers: null,
  images: null,
  volumes: null,
}
const DISK_USAGE = {
  images: { count: 12, totalSize: 1024 ** 3, reclaimableSize: 0 },
  containers: { count: 3, totalSize: 0, reclaimableSize: 0 },
  volumes: { count: 4, totalSize: 0, reclaimableSize: 0 },
  buildCache: { count: 0, totalSize: 0, reclaimableSize: 0 },
}
const user = (role: string) => ({ username: 'u', role, setupRequired: false })

test('lists UP and DOWN environments and filters by name', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', { body: user('VIEWER') })
  api.mock('GET', '/api/hosts/overview', { body: [local, staging] })
  await page.goto('/')

  await expect(page.getByRole('link', { name: /Local/ })).toContainText('16 GiB')
  await expect(page.getByRole('link', { name: /Staging/ })).toContainText('The connection timed out.')

  await page.getByRole('searchbox', { name: 'Search environments' }).fill('stag')
  await expect(page.getByRole('link', { name: /Local/ })).toHaveCount(0)
  await expect(page.getByRole('link', { name: /Staging/ })).toBeVisible()
})

test('viewer has no add button and sees the empty hint', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', { body: user('VIEWER') })
  api.mock('GET', '/api/hosts/overview', { body: [] })
  await page.goto('/')

  await expect(page.getByText('No environments yet, ask an admin.')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Add environment' })).toHaveCount(0)
})

test('admin adds a local environment and sees it in the list', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', { body: user('ADMIN') })
  api.mock('GET', '/api/hosts/overview', { body: [] })
  await page.goto('/')

  await page.getByRole('button', { name: 'Add environment' }).first().click()
  const dialog = page.getByRole('dialog', { name: 'Add environment' })
  await dialog.getByLabel('Name').fill('Local')
  api.mock('POST', '/api/hosts', { status: 201, body: { id: 'a', name: 'Local' } })
  api.mock('GET', '/api/hosts/overview', { body: [local] })
  await dialog.getByRole('button', { name: 'Add environment' }).click()

  await expect(dialog).toHaveCount(0)
  await expect(page.getByRole('link', { name: /Local/ })).toBeVisible()
})

test('shows an error when the overview cannot be loaded', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', { body: user('ADMIN') })
  api.mock('GET', '/api/hosts/overview', { status: 500, body: { code: 'INTERNAL' } })
  await page.goto('/')

  await expect(page.getByRole('alert')).toContainText('Could not load the environments')
})

test('opens the dashboard and navigates back', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', { body: user('OPERATOR') })
  api.mock('GET', '/api/hosts/overview', { body: [local] })
  api.mock('GET', '/api/hosts/a/disk-usage', { body: DISK_USAGE })
  api.mock('GET', '/api/hosts/a/proxy/routes', { body: [] })
  await page.goto('/')

  await page.getByRole('link', { name: /Local/ }).click()
  await expect(page).toHaveURL(/\/environments\/a$/)
  await expect(page.getByRole('heading', { name: 'Local' })).toBeVisible()

  await page.getByRole('main').getByRole('link', { name: 'Environments' }).click()
  await expect(page).toHaveURL(/\/$/)
  await expect(page.getByRole('link', { name: /Local/ })).toBeVisible()
})

test('admin renames and deletes an environment', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', { body: user('ADMIN') })
  api.mock('GET', '/api/hosts/overview', { body: [local] })
  await page.goto('/')

  await chooseAction(page, 'Local', 'Rename')
  const rename = page.getByRole('dialog', { name: 'Rename environment' })
  await rename.getByLabel('Name').fill('Production')
  api.mock('PATCH', '/api/hosts/a', { body: { id: 'a', name: 'Production' } })
  api.mock('GET', '/api/hosts/overview', { body: [{ ...local, name: 'Production' }] })
  await rename.getByRole('button', { name: 'Save' }).click()
  await expect(rename).toHaveCount(0)
  await expect(page.getByRole('link', { name: /Production/ })).toBeVisible()

  await chooseAction(page, 'Production', 'Delete')
  const confirm = page.getByRole('dialog', { name: 'Delete environment' })
  await expect(confirm).toContainText('Production')
  await expect(confirm).toContainText('deployments')
  api.mock('DELETE', '/api/hosts/a', { status: 204 })
  api.mock('GET', '/api/hosts/overview', { body: [] })
  await confirm.getByRole('button', { name: 'Delete' }).click()
  await expect(confirm).toHaveCount(0)
  await expect(page.getByText('No environments yet. Add your first Docker environment.')).toBeVisible()
})

test('rename shows a taken name inline', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', { body: user('ADMIN') })
  api.mock('GET', '/api/hosts/overview', { body: [local] })
  await page.goto('/')

  await chooseAction(page, 'Local', 'Rename')
  const rename = page.getByRole('dialog', { name: 'Rename environment' })
  await rename.getByLabel('Name').fill('Staging')
  api.mock('PATCH', '/api/hosts/a', { status: 409, body: { code: 'HOST_NAME_TAKEN' } })
  await rename.getByRole('button', { name: 'Save' }).click()
  await expect(rename.getByRole('alert')).toContainText('already in use')
})

test('operator sees no rename or delete actions', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', { body: user('OPERATOR') })
  api.mock('GET', '/api/hosts/overview', { body: [local] })
  await page.goto('/')

  await expect(page.getByRole('link', { name: /Local/ })).toBeVisible()
  await expect(page.getByRole('button', { name: /Actions for/ })).toHaveCount(0)
})
