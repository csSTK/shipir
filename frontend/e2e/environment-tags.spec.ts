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
  tags: ['eu', 'prod'],
}
const berlin = {
  id: 'c',
  name: 'Berlin',
  connectionType: 'TCP',
  endpoint: 'tcp://berlin.example:2376',
  status: 'UP',
  error: null,
  dockerVersion: '27.0.0',
  cpus: 4,
  memoryBytes: 8 * 1024 ** 3,
  containers: { running: 1, stopped: 0, healthy: 1, unhealthy: 0 },
  images: 3,
  volumes: 1,
  tags: ['eu'],
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
  tags: [],
}
const user = (role: string) => ({ username: 'u', role, setupRequired: false })

test('viewer sees tag chips and filters by tag, the selection lives in the URL', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', { body: user('VIEWER') })
  api.mock('GET', '/api/hosts/overview', { body: [local, berlin, staging] })
  await page.goto('/')

  const localCard = page.getByRole('link', { name: /Local/ })
  await expect(localCard.getByRole('list', { name: 'Tags' })).toContainText('prod')
  await expect(localCard.getByRole('list', { name: 'Tags' })).toContainText('eu')

  await page.getByRole('button', { name: 'prod', exact: true }).click()
  await expect(page).toHaveURL(/tag=prod/)
  await expect(page.getByRole('link', { name: /Local/ })).toBeVisible()
  await expect(page.getByRole('link', { name: /Berlin/ })).toHaveCount(0)

  await page.getByRole('button', { name: 'eu', exact: true }).click()
  await expect(page.getByRole('button', { name: 'eu', exact: true })).toHaveAttribute('aria-pressed', 'true')
  await expect(page.getByRole('link', { name: /Berlin/ })).toHaveCount(0)
  await expect(page.getByRole('link', { name: /Local/ })).toBeVisible()
})

test('a tag no environment carries shows the tag empty state', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', { body: user('VIEWER') })
  api.mock('GET', '/api/hosts/overview', { body: [berlin, staging] })
  await page.goto('/?tag=prod')

  await expect(page.getByText('No environments with this tag')).toBeVisible()
  await expect(page.getByRole('link', { name: /Berlin/ })).toHaveCount(0)
})

test('a search term that removes every tagged match shows the neutral empty state', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', { body: user('VIEWER') })
  api.mock('GET', '/api/hosts/overview', { body: [local, berlin, staging] })
  await page.goto('/?tag=eu')

  await page.getByRole('searchbox', { name: 'Search environments' }).fill('xyz')
  await expect(page.getByText('No environments match your filters')).toBeVisible()
})

test('operator sees the tags but no tag editing', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', { body: user('OPERATOR') })
  api.mock('GET', '/api/hosts/overview', { body: [local] })
  await page.goto('/')

  await expect(page.getByRole('link', { name: /Local/ }).getByRole('list', { name: 'Tags' })).toContainText('prod')
  await expect(page.getByRole('button', { name: 'Actions for Local' })).toHaveCount(0)
})

test('admin edits tags with the chip input and sees them on the card', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', { body: user('ADMIN') })
  api.mock('GET', '/api/hosts/overview', { body: [local] })
  await page.goto('/')

  await page.getByRole('button', { name: 'Actions for Local' }).click()
  await page.getByRole('menuitem', { name: 'Edit tags' }).click()
  const dialog = page.getByRole('dialog', { name: 'Edit tags' })
  const input = dialog.getByRole('textbox', { name: 'Tags' })

  await input.fill('Bad Tag')
  await input.press('Enter')
  await expect(dialog.getByRole('alert')).toContainText(/lowercase letters, digits or hyphens/i)

  await input.fill('edge')
  await input.press('Enter')
  await expect(dialog.getByRole('button', { name: 'Remove tag edge' })).toBeVisible()
  await dialog.getByRole('button', { name: 'Remove tag prod' }).click()
  await expect(dialog.getByRole('button', { name: 'Remove tag prod' })).toHaveCount(0)

  api.mock('PUT', '/api/hosts/a/tags', { body: { ...local, tags: ['edge', 'eu'] } })
  api.mock('GET', '/api/hosts/overview', { body: [{ ...local, tags: ['edge', 'eu'] }] })
  await dialog.getByRole('button', { name: 'Save' }).click()

  await expect(dialog).toHaveCount(0)
  const card = page.getByRole('link', { name: /Local/ })
  await expect(card.getByRole('list', { name: 'Tags' })).toContainText('edge')
  await expect(card.getByRole('list', { name: 'Tags' })).not.toContainText('prod')
})

test('the top bar environment switcher groups environments by their first tag', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', { body: user('VIEWER') })
  api.mock('GET', '/api/hosts/overview', { body: [staging, berlin, local] })
  api.mock('GET', '/api/hosts/a/images', { body: [] })
  await page.goto('/environments/a/images')

  await page.getByRole('button', { name: /^Environment:/ }).click()
  const menu = page.getByRole('menu')
  await expect(menu.getByRole('menuitem')).toHaveCount(3)
  const sequence = await menu.evaluate((el) =>
    Array.from(el.children).map((child) => (child.getAttribute('role') === 'separator' ? 'sep' : child.textContent)),
  )
  // Local and Berlin share the first tag "eu"; untagged Staging goes last.
  expect(sequence).toEqual(['Berlin', 'Local', 'sep', 'Staging'])
})
