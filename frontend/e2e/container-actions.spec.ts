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
  containers: { running: 1, stopped: 1, healthy: 0, unhealthy: 0 },
  images: 1,
  volumes: 1,
}
const as = (role: string) => ({ body: { username: 'u', role, setupRequired: false } })
const base = { image: 'x', status: 'Up', health: null, created: '2026-10-01T10:00:00Z', ports: [], composeProject: null, composeService: null }
const containers = [
  { ...base, id: 'c1', name: 'web', state: 'running' },
  { ...base, id: 'c2', name: 'old', state: 'exited' },
  { ...base, id: 'c3', name: 'frozen', state: 'paused' },
]

test.beforeEach(({ api }) => {
  api.mock('GET', '/api/hosts/overview', { body: [local] })
  api.mock('GET', '/api/hosts/a/containers', { body: containers })
  api.mock('GET', '/api/hosts/a/images', { body: [] })
  api.mock('GET', '/api/hosts/a/deployments', { body: [] })
})

test('operator sees state-aware icon buttons and starts a container', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', as('OPERATOR'))
  api.mock('POST', '/api/hosts/a/containers/c2/start', { status: 204 })
  await page.goto('/environments/a/containers')
  await expect(page.getByRole('button', { name: 'Stop web' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Start web', exact: true })).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Unpause frozen' })).toBeVisible()
  const request = page.waitForRequest((r) => r.method() === 'POST' && r.url().endsWith('/containers/c2/start'))
  await page.getByRole('button', { name: 'Start old' }).click()
  await request
})

test('kill asks for confirmation', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', as('ADMIN'))
  api.mock('POST', '/api/hosts/a/containers/c1/kill', { status: 204 })
  await page.goto('/environments/a/containers')
  await page.getByRole('button', { name: 'More actions for web' }).click()
  await page.getByRole('menuitem', { name: 'Kill' }).click()
  const dialog = page.getByRole('dialog', { name: 'Kill container' })
  const request = page.waitForRequest((r) => r.method() === 'POST' && r.url().endsWith('/containers/c1/kill'))
  await dialog.getByRole('button', { name: 'Kill' }).click()
  await request
  await expect(dialog).toBeHidden()
})

test('state conflict shows an inline error', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', as('OPERATOR'))
  api.mock('POST', '/api/hosts/a/containers/c1/pause', {
    status: 409,
    body: { code: 'INVALID_CONTAINER_STATE', message: 'x' },
  })
  await page.goto('/environments/a/containers')
  await page.getByRole('button', { name: 'More actions for web' }).click()
  await page.getByRole('menuitem', { name: 'Pause' }).click()
  await expect(page.getByRole('alert')).toContainText('not in a state that allows this action')
})

test('viewer has no action buttons', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', as('VIEWER'))
  await page.goto('/environments/a/containers')
  await expect(page.getByRole('link', { name: 'Details of web' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Stop web' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Logs web' })).toBeVisible()
})

test.describe('table column layout at 1440px', () => {
  test.use({ viewport: { width: 1440, height: 900 } })

  test('port chips never overlap the pinned actions cell and the ports column keeps its width', async ({ page, api }) => {
    api.mock('GET', '/api/auth/me', as('ADMIN'))
    api.mock('GET', '/api/hosts/a/containers', { body: [{ ...base, id: 'c1', name: 'web', state: 'running', ports: ['8080:80/tcp'] }, ...containers.slice(1)] })
    await page.goto('/environments/a/containers')
    const chip = page.locator('.containers__ports li').first()
    await expect(chip).toBeVisible()
    const actions = page.locator('td.rtable__actions-cell').first()
    const [c, a] = [await chip.boundingBox(), await actions.boundingBox()]
    expect(c && a).toBeTruthy()
    expect(c!.x + c!.width).toBeLessThanOrEqual(a!.x)
    const ports = await page.locator('th[data-col="ports"]').boundingBox()
    expect(ports!.width).toBeGreaterThanOrEqual(120)
    // the buttons of every row sit inside their cell
    for (const row of await page.locator('td.rtable__actions-cell').all()) {
      const cell = await row.boundingBox()
      for (const btn of await row.getByRole('button').all()) {
        const b = await btn.boundingBox()
        expect(b!.x).toBeGreaterThanOrEqual(cell!.x)
        expect(b!.x + b!.width).toBeLessThanOrEqual(cell!.x + cell!.width)
      }
    }
  })
})

test.describe('table column layout at 375px', () => {
  test.use({ viewport: { width: 375, height: 800 } })

  test('the name column is capped on phones so the state fits before the pinned actions', async ({ page, api }) => {
    api.mock('GET', '/api/auth/me', as('ADMIN'))
    await page.goto('/environments/a/containers')
    await expect(page.getByRole('link', { name: 'Details of web' })).toBeVisible()
    const name = await page.locator('th[data-col="name"]').boundingBox()
    expect(name!.width).toBeGreaterThanOrEqual(100)
    const state = await page.locator('th[data-col="state"]').boundingBox()
    const actions = await page.locator('th.rtable__actions-cell').boundingBox()
    expect(state!.x + state!.width).toBeLessThanOrEqual(actions!.x + 1)
  })
})
