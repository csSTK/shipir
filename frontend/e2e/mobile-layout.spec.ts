import type { Locator, Page } from '@playwright/test'

import { expect, test } from './fixtures/api.ts'
import { mockCommon, mockContainerDetails, mockUsers } from './fixtures/visual-data.ts'

// Phone layout (375px): tables keep their actions clear of the data, the container detail page fits the screen
// and the floating assistant button never covers a control.

test.beforeEach(async ({ page, api }) => {
  mockCommon(api)
  mockUsers(api)
  mockContainerDetails(api)
  await page.setViewportSize({ width: 375, height: 800 })
})

async function noPageScroll(page: Page) {
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)
  expect(overflow, 'page-level horizontal overflow in px').toBeLessThanOrEqual(0)
}

async function intersects(a: Locator, b: Locator) {
  const [x, y] = [await a.boundingBox(), await b.boundingBox()]
  if (!x || !y) throw new Error('element not laid out')
  return x.x < y.x + y.width && y.x < x.x + x.width && x.y < y.y + y.height && y.y < x.y + x.height
}

for (const target of [
  { name: 'containers', path: '/environments/a/containers', row: 'web-with-a-rather-long-container-name', actions: 'More actions for web-with-a-rather-long-container-name' },
  { name: 'users', path: '/settings/users', row: 'anna', actions: 'More actions for anna' },
]) {
  test(`${target.name} table at 375px: actions never overlap the status, no page scroll`, async ({ page }) => {
    await page.goto(target.path)
    const row = page.getByRole('row').filter({ hasText: target.row }).first()
    await expect(row).toBeVisible()
    // phones show one overflow button instead of the icon buttons
    const actions = row.getByRole('button', { name: target.actions })
    await expect(actions).toBeVisible()
    await expect(row.locator('.rtable__actions button')).toHaveCount(1)
    // name and actions are on screen without scrolling the table
    await expect(row.getByText(target.row, { exact: true }).first()).toBeVisible()
    // scrolled to the end, the last column (status/role) ends where the pinned actions begin, so no cell is covered
    await page.locator('.rtable__scroll').evaluate((el) => el.scrollTo({ left: el.scrollWidth }))
    for (const cell of await row.locator('td[data-col]').all()) expect(await intersects(actions, cell)).toBe(false)
    await noPageScroll(page)
  })
}

test('container detail at 375px fits the screen, tabs scroll inside their row, changes sit in a menu', async ({ page }) => {
  await page.goto('/environments/a/containers/1')
  await expect(page.getByRole('heading', { name: 'web-with-a-rather-long-container-name' })).toBeVisible()
  await noPageScroll(page)
  await expect(page.getByRole('button', { name: 'Edit', exact: true })).toHaveCount(0)
  await page.getByRole('button', { name: 'Change container' }).click()
  await expect(page.getByRole('menuitem', { name: 'Resource limits' })).toBeVisible()
  await page.keyboard.press('Escape')
  const list = page.getByRole('tablist')
  const long = page.getByRole('tab', { name: 'Storage & network' })
  const short = page.getByRole('tab', { name: 'Labels' })
  expect((await long.boundingBox())!.height, 'long tab label stays on one line').toBe((await short.boundingBox())!.height)
  // the row scrolls inside itself and its last tab can be reached
  expect(await list.evaluate((el) => el.scrollWidth > el.clientWidth)).toBe(true)
  const last = page.getByRole('tab').last()
  await last.scrollIntoViewIfNeeded()
  const [lb, rb] = [(await last.boundingBox())!, (await list.boundingBox())!]
  expect(lb.x + lb.width, 'last tab fully inside the list').toBeLessThanOrEqual(rb.x + rb.width + 1)
  await noPageScroll(page)
  // a focused tab's ring is not clipped by the list, and the list has no vertical scroll
  await short.focus()
  await page.keyboard.press('Shift+Tab')
  await page.keyboard.press('Tab')
  await expect(short).toBeFocused()
  expect(await list.evaluate((el) => el.scrollHeight - el.clientHeight)).toBe(0)
  const ring = await short.evaluate((el) => ({ w: getComputedStyle(el).outlineWidth, off: getComputedStyle(el).outlineOffset }))
  expect(parseFloat(ring.w)).toBeGreaterThan(0)
  expect(parseFloat(ring.off)).toBeLessThanOrEqual(0)
})

for (const path of ['/settings/ai', '/account']) {
  test(`assistant button leaves room below the last control on ${path} at 375px`, async ({ page }) => {
    await page.goto(path)
    await expect(page.getByRole('main').first()).toBeVisible()
    await expect(page.getByRole('button').last()).toBeVisible()
    await page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight))
    const dock = page.getByRole('button', { name: /assistant/i }).first()
    await expect(dock).toBeVisible()
    const dockTop = (await dock.boundingBox())!.y
    // everything the page renders ends above the floating button once scrolled to the end
    const contentBottom = await page.evaluate(() => {
      const kids = [...(document.querySelector('.shell__content')?.querySelectorAll('*') ?? [])]
      return Math.max(...kids.map((el) => el.getBoundingClientRect().bottom))
    })
    expect(contentBottom).toBeLessThanOrEqual(dockTop)
  })
}

test('Save on /settings/ai is clickable at 375px', async ({ page }) => {
  await page.goto('/settings/ai')
  const save = page.getByRole('button', { name: 'Save' }).last()
  await expect(save).toBeVisible()
  await page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight))
  const box = (await save.boundingBox())!
  const hit = await page.evaluate(
    ([x, y]) => document.elementFromPoint(x, y)?.closest('button')?.textContent ?? '',
    [box.x + box.width / 2, box.y + box.height / 2],
  )
  expect(hit).toContain('Save')
})
