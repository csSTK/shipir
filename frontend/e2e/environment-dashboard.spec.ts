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
  containers: { running: 5, stopped: 2, healthy: 3, unhealthy: 1 },
  images: 12,
  volumes: 4,
}
const staging = {
  ...local,
  id: 'b',
  name: 'Staging',
  connectionType: 'TCP',
  status: 'DOWN',
  error: 'TIMEOUT',
  dockerVersion: null,
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
const me = { body: { username: 'u', role: 'VIEWER', setupRequired: false } }

test('overview -> dashboard -> sidebar items', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', me)
  api.mock('GET', '/api/notifications/unread-count', { body: { count: 0 } })
  api.mock('GET', '/api/hosts/overview', { body: [local, staging] })
  api.mock('GET', '/api/hosts/a/disk-usage', { body: DISK_USAGE })
  api.mock('GET', '/api/hosts/a/deployments', { body: [] })
  api.mock('GET', '/api/hosts/a/networks', { body: [] })
  api.mock('GET', '/api/hosts/a/containers', { body: [] })
  api.mock('GET', '/api/hosts/a/images', { body: [] })
  api.mock('GET', '/api/hosts/a/deployments', { body: [] })
  api.mock('GET', '/api/hosts/a/volumes', { body: [] })
  await page.goto('/')
  const nav = page.getByRole('navigation', { name: 'Main' })
  await expect(nav.getByRole('group', { name: 'Local' })).toHaveCount(0)

  await page.getByRole('link', { name: /Local/ }).click()
  await expect(page).toHaveURL(/\/environments\/a$/)
  await expect(page.getByText('Docker 27.1.2')).toBeVisible()
  const group = nav.getByRole('group', { name: 'Local' })
  await expect(group.getByRole('link', { name: 'Dashboard' })).toHaveAttribute('aria-current', 'page')

  await page.getByRole('link', { name: /Containers/ }).last().click()
  await expect(page).toHaveURL(/\/environments\/a\/containers$/)
  await expect(group.getByRole('link', { name: 'Containers' })).toHaveAttribute('aria-current', 'page')

  await group.getByRole('link', { name: 'Volumes' }).click()
  await expect(page).toHaveURL(/\/environments\/a\/volumes$/)
  await group.getByRole('link', { name: 'Dashboard' }).click()
  await expect(page).toHaveURL(/\/environments\/a$/)

  await nav.getByRole('link', { name: 'Environments' }).click()
  await expect(nav.getByRole('group', { name: 'Local' })).toHaveCount(0)
})

test('tile counts and a DOWN environment', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', me)
  api.mock('GET', '/api/notifications/unread-count', { body: { count: 0 } })
  api.mock('GET', '/api/hosts/overview', { body: [local, staging] })
  api.mock('GET', '/api/hosts/a/disk-usage', { body: DISK_USAGE })
  api.mock('GET', '/api/hosts/a/deployments', { body: [{ name: 'web' }, { name: 'db' }, { name: 'cache' }] })
  api.mock('GET', '/api/hosts/a/networks', { body: [{ id: 'n1' }, { id: 'n2' }] })
  await page.goto('/environments/a')
  await expect(page.getByRole('main').getByRole('link', { name: /Deployments/ })).toContainText('3')
  await expect(page.getByRole('main').getByRole('link', { name: /Networks/ })).toContainText('2')
  await expect(page.getByRole('main').getByRole('link', { name: /Containers/ })).toContainText('7')
  await expect(page.getByRole('main').getByRole('link', { name: /Images/ })).toContainText('12')

  await page.goto('/environments/b')
  await expect(page.getByRole('alert')).toContainText('The connection timed out.')
})

test('unknown environment shows not found', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', me)
  api.mock('GET', '/api/notifications/unread-count', { body: { count: 0 } })
  api.mock('GET', '/api/hosts/overview', { body: [local] })
  await page.goto('/environments/nope')
  await expect(page.getByRole('heading', { name: 'Environment not found' })).toBeVisible()
})

test('sidebar section works on a narrow screen', async ({ page, api }) => {
  await page.setViewportSize({ width: 390, height: 800 })
  api.mock('GET', '/api/auth/me', me)
  api.mock('GET', '/api/notifications/unread-count', { body: { count: 0 } })
  api.mock('GET', '/api/hosts/overview', { body: [local] })
  api.mock('GET', '/api/hosts/a/disk-usage', { body: DISK_USAGE })
  api.mock('GET', '/api/hosts/a/deployments', { body: [] })
  api.mock('GET', '/api/hosts/a/networks', { body: [] })
  api.mock('GET', '/api/hosts/a/images', { body: [] })
  await page.goto('/environments/a')
  await page.getByRole('button', { name: 'Navigation' }).click()
  await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Images' }).click()
  await expect(page).toHaveURL(/\/environments\/a\/images$/)
  // Wait for the page's own request to be answered so it cannot fire after the test ends.
  await expect(page.getByRole('heading', { name: 'Images' })).toBeVisible()
})

test('resource tiles fill every row: 2 columns on a phone, no orphan on desktop', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', me)
  api.mock('GET', '/api/notifications/unread-count', { body: { count: 0 } })
  api.mock('GET', '/api/hosts/overview', { body: [local] })
  api.mock('GET', '/api/hosts/a/disk-usage', { body: DISK_USAGE })
  api.mock('GET', '/api/hosts/a/deployments', { body: [] })
  api.mock('GET', '/api/hosts/a/networks', { body: [] })
  const tileLabels = ['Deployments', 'Containers', 'Images', 'Networks', 'Volumes', 'Domains']

  for (const { width, height, columns } of [
    { width: 375, height: 800, columns: [2] },
    { width: 1440, height: 900, columns: [3, 6] },
  ]) {
    await page.setViewportSize({ width, height })
    await page.goto('/environments/a')
    await expect(page.getByRole('main').getByRole('link', { name: /Containers/ })).toContainText('7')

    const boxes = await Promise.all(
      tileLabels.map((label) => page.getByRole('main').getByRole('link', { name: new RegExp(label) }).boundingBox()),
    )
    // Group tiles into rows by their top edge; every row must hold the same number of tiles.
    const rows = new Map<number, number>()
    for (const box of boxes) {
      if (!box) throw new Error('tile not rendered')
      rows.set(box.y, (rows.get(box.y) ?? 0) + 1)
    }
    const perRow = [...rows.values()]
    expect(columns).toContain(perRow[0])
    expect(perRow.every((count) => count === perRow[0]), `tiles per row at ${width}px: ${perRow}`).toBe(true)
  }
})
