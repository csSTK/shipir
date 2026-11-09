import { expect, mockDeploymentCards, test } from './fixtures/api.ts'

async function openAdvanced(page: import('@playwright/test').Page, n: number) {
  await page.getByRole('group', { name: `Service ${n}` }).getByText('Advanced options').click()
}

const host = {
  id: 'a',
  name: 'Local',
  connectionType: 'LOCAL',
  endpoint: 'unix:///var/run/docker.sock',
  status: 'UP',
  error: null,
  dockerVersion: '27.1.2',
  cpus: 8,
  memoryBytes: 16 * 1024 ** 3,
  containers: { running: 0, stopped: 0, healthy: 0, unhealthy: 0 },
  images: 0,
  volumes: 0,
}
const BASE = '/api/hosts/a/managed-deployments'
const yaml = 'services:\n  web:\n    image: nginx:1.27\n    networks:\n      - front\n  db:\n    image: postgres:16\nnetworks:\n  front: {}\n'
const created = { id: 's1', name: 'shop', status: 'STOPPED', source: 'FILE', lastDeployedAt: null, createdAt: '2026-10-01T10:00:00Z', yaml, envKeys: ['DB_PASSWORD'] }

function mockCommon(api: import('./fixtures/api.ts').ApiMock) {
  api.mock('GET', '/api/auth/me', { body: { username: 'u', role: 'OPERATOR', setupRequired: false } })
  api.mock('GET', '/api/hosts/overview', { body: [host] })
  api.mock('GET', '/api/hosts/a/deployments', { body: [] })
  api.mock('GET', BASE, { body: [] })
}

test('operator builds a two-service deployment in the wizard, previews it and creates it', async ({ page, api }) => {
  mockCommon(api)
  api.mock('POST', '/api/compose/generate', { body: { yaml } })
  api.mock('POST', BASE, { status: 201, body: created })
  api.mock('GET', `${BASE}/s1`, { body: created })
  mockDeploymentCards(api, `${BASE}/s1`)

  await page.goto('/environments/a/deployments')
  await page.getByRole('button', { name: 'Add deployment' }).click()
  await page.getByLabel('Name', { exact: true }).fill('shop')
  await page.getByRole('radio', { name: 'Form wizard' }).check()

  await page.getByLabel('Service 1 name').fill('web')
  await page.getByLabel('Service 1 image').fill('nginx:1.27')
  await page.getByRole('button', { name: 'Add service' }).click()
  await page.getByLabel('Service 2 name').fill('db')
  await page.getByLabel('Service 2 image').fill('postgres:16')

  await page.getByRole('button', { name: 'Add network' }).click()
  await page.getByLabel('Network 1 name').fill('front')
  await openAdvanced(page, 1)
  await page.getByRole('group', { name: 'Service 1' }).getByRole('checkbox', { name: 'front' }).check()

  await openAdvanced(page, 2)
  await page.getByRole('button', { name: 'Add env var to service 2' }).click()
  await page.getByLabel('Service 2 env 1 name').fill('POSTGRES_PASSWORD')
  await page.getByRole('group', { name: 'Service 2' }).getByLabel('From deployment variable').check()
  await page.getByLabel('Service 2 env 1 reference').fill('DB_PASSWORD')

  await page.getByRole('button', { name: 'Preview YAML' }).click()
  await expect(page.getByRole('heading', { name: 'Generated Compose file' })).toBeVisible()
  await page.getByRole('button', { name: 'Continue in editor' }).click()

  // The referenced variable is waiting in the env editor, ready for its value.
  await expect(page.getByLabel('Variable 1 name')).toHaveValue('DB_PASSWORD')
  await page.getByLabel('Variable 1 value').fill('s3cret')

  const request = page.waitForRequest((r) => r.method() === 'POST' && r.url().endsWith(BASE))
  await page.getByRole('button', { name: 'Create deployment' }).click()
  const body = (await request).postDataJSON()
  expect(body).toEqual({ name: 'shop', yaml, env: [{ key: 'DB_PASSWORD', value: 's3cret' }] })
  await expect(page).toHaveURL(/\/environments\/a\/deployments\/s1$/)
})

test('invalid wizard input is caught locally and server violations are listed', async ({ page, api }) => {
  mockCommon(api)
  api.mock('POST', '/api/compose/generate', {
    status: 400,
    body: { code: 'INVALID_COMPOSE_SPEC', message: 'invalid', details: ['services[0].ports[0]: host port 80 is used twice'] },
  })

  await page.goto('/environments/a/deployments')
  await page.getByRole('button', { name: 'Add deployment' }).click()
  await page.getByRole('radio', { name: 'Form wizard' }).check()

  await page.getByRole('button', { name: 'Preview YAML' }).click()
  await expect(page.getByText('Fix the highlighted fields first.')).toBeVisible()
  await expect(page.getByText('Use lowercase letters, digits, hyphens and underscores.')).toBeVisible()

  await page.getByLabel('Service 1 name').fill('web')
  await page.getByLabel('Service 1 image').fill('nginx')
  await openAdvanced(page, 1)
  await page.getByRole('button', { name: 'Add port to service 1' }).click()
  await page.getByLabel('Service 1 port 1 container').fill('99999')
  await page.getByRole('button', { name: 'Preview YAML' }).click()
  await expect(page.getByText('Use a port from 1 to 65535.')).toBeVisible()

  await page.getByLabel('Service 1 port 1 container').fill('80')
  await page.getByRole('button', { name: 'Preview YAML' }).click()
  await expect(page.getByText('services[0].ports[0]: host port 80 is used twice')).toBeVisible()
})

test('at 375px both tab rows stay on one line and the first step folds the advanced sections', async ({ page, api }) => {
  mockCommon(api)
  await page.setViewportSize({ width: 375, height: 800 })
  await page.goto('/environments/a/deployments')
  await page.getByRole('button', { name: 'Add deployment' }).click()
  await page.getByRole('radio', { name: 'Form wizard' }).check()

  // Geometry check (no visual baselines on this branch): every tab is as tall as one line of text and the row fits the dialog.
  for (const group of ['Source', 'Editing mode']) {
    const row = page.getByRole('radiogroup', { name: group })
    const rowBox = (await row.boundingBox())!
    const dialogBox = (await page.getByRole('dialog').boundingBox())!
    expect(rowBox.x + rowBox.width).toBeLessThanOrEqual(dialogBox.x + dialogBox.width + 1)
    const tops = new Set<number>()
    for (const tab of await row.locator('label').all()) tops.add(Math.round((await tab.boundingBox())!.y))
    expect(tops.size).toBe(1)
  }
  const source = page.getByRole('radiogroup', { name: 'Source' })
  // The row scrolls inside itself when the tabs do not fit; the last tab can be reached.
  expect(await source.evaluate((el) => el.scrollWidth > el.clientWidth)).toBe(true)
  await source.getByText('Git repository').scrollIntoViewIfNeeded()
  await expect(source.getByText('Git repository')).toBeInViewport()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)

  // Only the essentials are shown until the disclosure is opened.
  await expect(page.getByLabel('Service 1 name')).toBeVisible()
  await expect(page.getByLabel('Service 1 image')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Add port to service 1' })).toBeHidden()
  await expect(page.getByLabel('Service 1 domain host')).toBeHidden()
  await openAdvanced(page, 1)
  await expect(page.getByRole('button', { name: 'Add port to service 1' })).toBeVisible()
  await expect(page.getByLabel('Service 1 domain host')).toBeVisible()
})

test('at 375px each stacked row input shows a visible label and keeps its accessible name', async ({ page, api }) => {
  mockCommon(api)
  await page.setViewportSize({ width: 375, height: 800 })
  await page.goto('/environments/a/deployments')
  await page.getByRole('button', { name: 'Add deployment' }).click()
  await page.getByRole('radio', { name: 'Form wizard' }).check()
  await openAdvanced(page, 1)
  await page.getByRole('button', { name: 'Add port to service 1' }).click()
  await page.getByRole('button', { name: 'Add env var to service 1' }).click()

  const labelOf = (name: string) =>
    page.getByLabel(name).evaluate((el) => {
      const cell = el.closest('.wz-cell')!
      const style = getComputedStyle(cell, '::before')
      return { text: style.content.replace(/^"|" \/ ""$/g, ''), shown: getComputedStyle(cell).display !== 'contents' }
    })
  expect(await labelOf('Service 1 port 1 host')).toEqual({ text: 'Host', shown: true })
  expect(await labelOf('Service 1 port 1 protocol')).toEqual({ text: 'Protocol', shown: true })
  expect((await labelOf('Service 1 env 1 name')).text).toBe('Variable name')
  expect((await labelOf('Service 1 env 1 value')).text).toBe('Value')
  // The visible label is decorative: the accessible name stays exactly the original label (no "Host" prepended).
  await expect(page.getByRole('textbox', { name: 'Service 1 port 1 host', exact: true })).toBeVisible()
  const cell = page.getByLabel('Service 1 port 1 host').locator('xpath=ancestor::*[contains(@class,"wz-cell")]')
  expect(await cell.evaluate((el) => getComputedStyle(el, '::before').content)).toContain('/')
})
