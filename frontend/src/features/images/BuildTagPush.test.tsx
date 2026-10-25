import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../shared/api/client.ts'
import { ImagesPage } from './ImagesPage.tsx'

const apiFetch = vi.fn()
vi.mock('../../shared/api/client.ts', async (importActual) => ({
  ...(await importActual<typeof import('../../shared/api/client.ts')>()),
  apiFetch: (...args: unknown[]) => apiFetch(...args),
}))

let handlers: { onMessage: (body: string) => void } | null = null
vi.mock('../../shared/realtime/stomp.ts', () => ({
  subscribeToTopic: (_dest: string, h: typeof handlers) => {
    handlers = h
    return () => {}
  },
}))

const fetchMock = vi.fn()
beforeEach(() => vi.stubGlobal('fetch', fetchMock))
afterEach(() => {
  cleanup()
  apiFetch.mockReset()
  fetchMock.mockReset()
  vi.unstubAllGlobals()
  handlers = null
})

const images = [
  {
    id: 'sha256:aaaaaaaaaaaaaaaaaaaa',
    repoTags: ['nginx:latest', 'nginx:1.27'],
    size: 1,
    created: '2026-10-01T10:00:00Z',
    inUse: true,
  },
  {
    id: 'sha256:bbbbbbbbbbbbbbbbbbbb',
    repoTags: [],
    size: 1,
    created: '2026-09-01T10:00:00Z',
    inUse: false,
  },
]

function serve(role: string, overrides: Record<string, () => Promise<unknown>> = {}) {
  apiFetch.mockImplementation((path: string, init?: { method?: string }) => {
    const key = `${init?.method ?? 'GET'} ${path}`
    if (overrides[key]) return overrides[key]()
    if (path === '/auth/me') return Promise.resolve({ username: 'u', role, setupRequired: false })
    if (path === '/hosts/h1/images' && !init?.method) return Promise.resolve(images)
    if (path === '/git/credentials') return Promise.resolve([{ id: 'c1', name: 'github-pat' }])
    if (init?.method === 'POST' && path.endsWith('/push')) return Promise.resolve({ runId: 'run-9' })
    if (init?.method === 'POST') return Promise.resolve(undefined)
    return Promise.resolve([])
  })
}

function renderPage() {
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter initialEntries={['/environments/h1/images']}>
        <Routes>
          <Route path="/environments/:id/images" element={<ImagesPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

const frame = (f: object) => handlers!.onMessage(JSON.stringify(f))
const jsonResponse = (status: number, body: unknown) =>
  Promise.resolve(
    new Response(JSON.stringify(body), {
      status,
      headers: { 'Content-Type': 'application/json' },
    }),
  )

async function openBuild() {
  fireEvent.click(await screen.findByRole('button', { name: 'Build image' }))
  return screen.getByRole('dialog', { name: 'Build image' })
}

describe('build image dialog', () => {
  it('shows the Build button only to ADMIN', async () => {
    serve('OPERATOR')
    renderPage()
    await screen.findByText('nginx:latest')
    expect(screen.queryByRole('button', { name: 'Build image' })).not.toBeInTheDocument()
    cleanup()
    serve('ADMIN')
    renderPage()
    expect(await screen.findByRole('button', { name: 'Build image' })).toBeInTheDocument()
  })

  it('uploads the context as multipart with CSRF header and streams the output as text', async () => {
    document.cookie = 'XSRF-TOKEN=tok'
    serve('ADMIN')
    fetchMock.mockImplementation(() => jsonResponse(202, { runId: 'run-1' }))
    renderPage()
    const dialog = await openBuild()
    const file = new File(['x'], 'ctx.tar.gz')
    fireEvent.change(within(dialog).getByLabelText('Build context'), {
      target: { files: [file] },
    })
    fireEvent.change(within(dialog).getByLabelText('Image tag'), {
      target: { value: 'myapp:1.0' },
    })
    fireEvent.click(within(dialog).getByRole('switch', { name: 'Build without cache' }))
    fireEvent.click(within(dialog).getByRole('button', { name: 'Add build argument' }))
    fireEvent.change(within(dialog).getByLabelText('Build argument 1'), {
      target: { value: 'NODE_ENV=prod' },
    })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Build' }))
    expect(await within(dialog).findByRole('log')).toBeInTheDocument()
    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe('/api/hosts/h1/images/build')
    expect(init.method).toBe('POST')
    expect(init.headers['X-XSRF-TOKEN']).toBe('tok')
    const form = init.body as FormData
    expect(form.get('context')).toBeInstanceOf(File)
    expect(form.get('tag')).toBe('myapp:1.0')
    expect(form.get('dockerfile')).toBe('Dockerfile')
    expect(form.getAll('buildArg')).toEqual(['NODE_ENV=prod'])
    expect(form.get('noCache')).toBe('true')
    expect(form.has('gitUrl')).toBe(false)
    frame({ seq: 1, type: 'LINE', line: '<img src=x onerror=alert(1)>' })
    frame({ seq: 2, type: 'STATUS', status: 'DONE' })
    expect(await within(dialog).findByText('<img src=x onerror=alert(1)>')).toBeInTheDocument()
    expect(dialog.querySelector('img')).toBeNull()
    expect(await within(dialog).findByText('Succeeded')).toBeInTheDocument()
  })

  it('warns that build args are not for secrets', async () => {
    serve('ADMIN')
    renderPage()
    const dialog = await openBuild()
    fireEvent.click(within(dialog).getByRole('button', { name: 'Add build argument' }))
    expect(within(dialog).getByText(/visible in the image history/i)).toBeInTheDocument()
  })

  it('rejects an oversized or wrong file client-side and blocks submit', async () => {
    serve('ADMIN')
    renderPage()
    const dialog = await openBuild()
    fireEvent.change(within(dialog).getByLabelText('Image tag'), {
      target: { value: 'myapp:1.0' },
    })
    const big = new File(['x'], 'ctx.tar')
    Object.defineProperty(big, 'size', { value: 201 * 1024 * 1024 })
    fireEvent.change(within(dialog).getByLabelText('Build context'), {
      target: { files: [big] },
    })
    expect(within(dialog).getByRole('alert')).toHaveTextContent('200 MiB')
    expect(within(dialog).getByRole('button', { name: 'Build' })).toBeDisabled()
    fireEvent.change(within(dialog).getByLabelText('Build context'), {
      target: { files: [new File(['x'], 'ctx.zip')] },
    })
    expect(within(dialog).getByRole('alert')).toHaveTextContent('.tar')
    expect(within(dialog).getByRole('button', { name: 'Build' })).toBeDisabled()
  })

  it('validates the tag inline', async () => {
    serve('ADMIN')
    renderPage()
    const dialog = await openBuild()
    fireEvent.change(within(dialog).getByLabelText('Image tag'), {
      target: { value: 'Bad Tag' },
    })
    expect(within(dialog).getByText(/not a valid image reference/i)).toBeInTheDocument()
  })

  it('sends git fields and loads credentials only after choosing Git', async () => {
    serve('ADMIN')
    fetchMock.mockImplementation(() => jsonResponse(202, { runId: 'run-1' }))
    renderPage()
    const dialog = await openBuild()
    expect(apiFetch).not.toHaveBeenCalledWith('/git/credentials', expect.anything())
    fireEvent.click(within(dialog).getByRole('radio', { name: 'Git repository' }))
    fireEvent.change(within(dialog).getByLabelText('Repository URL'), {
      target: { value: 'https://github.com/o/r.git' },
    })
    fireEvent.change(within(dialog).getByLabelText('Branch, tag or commit'), {
      target: { value: 'main' },
    })
    await within(dialog).findByRole('option', { name: 'github-pat' })
    fireEvent.change(within(dialog).getByLabelText('Credential'), {
      target: { value: 'c1' },
    })
    fireEvent.change(within(dialog).getByLabelText('Image tag'), {
      target: { value: 'myapp:1.0' },
    })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Build' }))
    await within(dialog).findByRole('log')
    const form = fetchMock.mock.calls[0][1].body as FormData
    expect(form.get('gitUrl')).toBe('https://github.com/o/r.git')
    expect(form.get('gitRef')).toBe('main')
    expect(form.get('gitCredentialId')).toBe('c1')
    expect(form.has('context')).toBe(false)
  })

  it.each([
    [409, 'BUILD_IN_PROGRESS', /already running/i],
    [413, 'SIZE_LIMIT_EXCEEDED', /200 MiB/],
    [400, 'INVALID_ARCHIVE', /valid tar/i],
    [400, 'INVALID_BUILD_ARG', /A build argument is not valid/i],
  ])('maps %i %s inline and keeps the form', async (status, code, text) => {
    serve('ADMIN')
    fetchMock.mockImplementation(() => jsonResponse(status, { code, message: 'x' }))
    renderPage()
    const dialog = await openBuild()
    fireEvent.change(within(dialog).getByLabelText('Build context'), {
      target: { files: [new File(['x'], 'ctx.tar')] },
    })
    fireEvent.change(within(dialog).getByLabelText('Image tag'), {
      target: { value: 'myapp:1.0' },
    })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Build' }))
    expect(await within(dialog).findByText(text)).toBeInTheDocument()
    expect(within(dialog).getByLabelText('Image tag')).toHaveValue('myapp:1.0')
  })

  it('shows a failed build and refreshes the image list when the run ends', async () => {
    serve('ADMIN')
    fetchMock.mockImplementation(() => jsonResponse(202, { runId: 'run-1' }))
    renderPage()
    const dialog = await openBuild()
    fireEvent.change(within(dialog).getByLabelText('Build context'), {
      target: { files: [new File(['x'], 'ctx.tar')] },
    })
    fireEvent.change(within(dialog).getByLabelText('Image tag'), {
      target: { value: 'myapp:1.0' },
    })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Build' }))
    await within(dialog).findByRole('log')
    const before = apiFetch.mock.calls.filter((c) => c[0] === '/hosts/h1/images').length
    frame({ seq: 1, type: 'LINE', line: 'Build failed (BUILD_FAILED)' })
    frame({ seq: 2, type: 'STATUS', status: 'FAILED' })
    expect(await within(dialog).findByText('Failed')).toBeInTheDocument()
    await waitFor(() => expect(apiFetch.mock.calls.filter((c) => c[0] === '/hosts/h1/images').length).toBeGreaterThan(before))
  })
})

async function openMenu(name: string, item: string) {
  fireEvent.pointerDown(await screen.findByRole('button', { name: `More actions for ${name}` }), { button: 0, ctrlKey: false })
  fireEvent.click(await screen.findByRole('menuitem', { name: item }))
}

describe('tag and push', () => {
  it('hides Tag and Push for viewers', async () => {
    serve('VIEWER')
    renderPage()
    await screen.findByText('nginx:latest')
    expect(screen.queryByRole('button', { name: /More actions/ })).not.toBeInTheDocument()
  })

  it('tags an image with the first repoTag prefilled', async () => {
    serve('OPERATOR')
    renderPage()
    await openMenu('nginx:latest', 'Tag…')
    const dialog = screen.getByRole('dialog', { name: 'Tag image' })
    expect(within(dialog).getByLabelText('Repository')).toHaveValue('nginx')
    expect(within(dialog).getByLabelText('Tag')).toHaveValue('latest')
    fireEvent.change(within(dialog).getByLabelText('Repository'), {
      target: { value: 'registry.example.com/team/nginx' },
    })
    fireEvent.change(within(dialog).getByLabelText('Tag'), {
      target: { value: 'stable' },
    })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Tag' }))
    await waitFor(() =>
      expect(apiFetch).toHaveBeenCalledWith(
        '/hosts/h1/images/sha256%3Aaaaaaaaaaaaaaaaaaaaa/tag',
        expect.objectContaining({
          method: 'POST',
          body: {
            repository: 'registry.example.com/team/nginx',
            tag: 'stable',
          },
        }),
      ),
    )
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Tag image' })).not.toBeInTheDocument())
  })

  it('maps a tag error and offers Tag for a dangling image without prefill', async () => {
    serve('ADMIN', {
      'POST /hosts/h1/images/sha256%3Abbbbbbbbbbbbbbbbbbbb/tag': () => Promise.reject(new ApiError(400, 'x', 'INVALID_IMAGE_REFERENCE')),
    })
    renderPage()
    await openMenu('bbbbbbbbbbbb', 'Tag…')
    const dialog = screen.getByRole('dialog', { name: 'Tag image' })
    expect(within(dialog).getByLabelText('Repository')).toHaveValue('')
    fireEvent.change(within(dialog).getByLabelText('Repository'), {
      target: { value: 'app' },
    })
    fireEvent.change(within(dialog).getByLabelText('Tag'), {
      target: { value: 'v1' },
    })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Tag' }))
    expect(await within(dialog).findByText(/not a valid/i)).toBeInTheDocument()
  })

  it('pushes the chosen tag after confirmation and shows live output', async () => {
    serve('OPERATOR')
    renderPage()
    await openMenu('nginx:latest', 'Push…')
    const dialog = screen.getByRole('dialog', { name: 'Push image' })
    fireEvent.change(within(dialog).getByLabelText('Reference to push'), {
      target: { value: 'nginx:1.27' },
    })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Push' }))
    expect(await within(dialog).findByRole('log')).toBeInTheDocument()
    expect(apiFetch).toHaveBeenCalledWith(
      '/hosts/h1/images/push',
      expect.objectContaining({
        method: 'POST',
        body: { reference: 'nginx:1.27' },
      }),
    )
    frame({ seq: 1, type: 'LINE', line: 'Push failed (PUSH_FAILED)' })
    frame({ seq: 2, type: 'STATUS', status: 'FAILED' })
    expect(await within(dialog).findByText('Push failed (PUSH_FAILED)')).toBeInTheDocument()
    expect(await within(dialog).findByText('Failed')).toBeInTheDocument()
  })

  it('has no Push entry for an untagged image', async () => {
    serve('OPERATOR')
    renderPage()
    fireEvent.pointerDown(
      await screen.findByRole('button', {
        name: 'More actions for bbbbbbbbbbbb',
      }),
      { button: 0, ctrlKey: false },
    )
    expect(await screen.findByRole('menuitem', { name: 'Push…' })).toHaveAttribute('aria-disabled', 'true')
  })
})
