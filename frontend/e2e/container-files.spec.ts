import { expect, test, type ApiMock } from './fixtures/api.ts'

const host = {
  id: 'a', name: 'Local', connectionType: 'LOCAL', endpoint: 'unix:///var/run/docker.sock', status: 'UP', error: null,
  dockerVersion: '27.1.2', cpus: 8, memoryBytes: 16 * 1024 ** 3,
  containers: { running: 1, stopped: 0, healthy: 0, unhealthy: 0 }, images: 0, volumes: 0,
}
const details = {
  id: 'c1', name: 'web', image: 'nginx:1', state: 'running', created: '2026-10-01T10:00:00Z',
  startedAt: '2026-10-01T10:00:05Z', restartCount: 0, restartPolicy: { name: 'always', maxRetries: 0 },
  command: ['nginx'], env: [], mounts: [], labels: {}, networks: [], ports: [], health: null,
  composeProject: null, composeService: null,
}
const file = (name: string, type: string, path: string) => ({ name, path, type, size: 12, modified: '2026-10-01T10:00:00Z', mode: '0644', linkTarget: null })

const filesUrl = (url: URL) => url.pathname === '/api/hosts/a/containers/c1/files'

async function setup(api: ApiMock, role: string) {
  api.mock('GET', '/api/auth/me', { body: { username: 'u', role, setupRequired: false } })
  api.mock('GET', '/api/hosts/overview', { body: [host] })
  api.mock('GET', '/api/hosts/a/containers/c1', { body: details })
}

test('admin browses directories and uploads a file', async ({ page, api }) => {
  await setup(api, 'ADMIN')
  let uploaded = false
  await page.route(filesUrl, async (route) => {
    const req = route.request()
    const path = new URL(req.url()).searchParams.get('path')
    if (req.method() === 'PUT') {
      uploaded = true
      return route.fulfill({ status: 204 })
    }
    const body = path === '/etc' ? [file('hosts', 'FILE', '/etc/hosts')] : [file('etc', 'DIRECTORY', '/etc')]
    return route.fulfill({ json: body })
  })
  await page.goto('/environments/a/containers/c1')
  await page.getByRole('tab', { name: 'Files' }).click()
  await page.getByRole('button', { name: 'etc/' }).click()
  await expect(page.getByText('hosts', { exact: true })).toBeVisible()
  await expect(page.getByRole('link', { name: 'Download hosts' })).toHaveAttribute('href', /download\?path=%2Fetc%2Fhosts/)
  await page.getByLabel('File to upload').setInputFiles({ name: 'note.txt', mimeType: 'text/plain', buffer: Buffer.from('hi') })
  await expect(page.getByText('Uploaded note.txt to /etc')).toBeVisible()
  expect(uploaded).toBe(true)
})

test('too large directory shows an inline hint', async ({ page, api }) => {
  await setup(api, 'ADMIN')
  await page.route(filesUrl, (route) =>
    route.fulfill({ status: 413, json: { code: 'SIZE_LIMIT_EXCEEDED', message: 'x' } }),
  )
  await page.goto('/environments/a/containers/c1')
  await page.getByRole('tab', { name: 'Files' }).click()
  await expect(page.getByText(/too large to list/)).toBeVisible()
})

test('operator does not see the files panel', async ({ page, api }) => {
  await setup(api, 'OPERATOR')
  await page.goto('/environments/a/containers/c1')
  await expect(page.getByRole('heading', { name: 'web' })).toBeVisible()
  await expect(page.getByRole('tab', { name: 'Files' })).toHaveCount(0)
})
