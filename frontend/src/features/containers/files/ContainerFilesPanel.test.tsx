import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../../shared/api/client.ts'
import { ContainerFilesPanel } from './ContainerFilesPanel.tsx'

const apiFetch = vi.fn()
vi.mock('../../../shared/api/client.ts', async (importActual) => ({
  ...(await importActual<typeof import('../../../shared/api/client.ts')>()),
  apiFetch: (...args: unknown[]) => apiFetch(...args),
}))

afterEach(() => {
  cleanup()
  apiFetch.mockReset()
  vi.unstubAllGlobals()
})

const entry = (name: string, type: string, path: string, size = 10) => ({
  name,
  path,
  type,
  size,
  modified: '2026-10-01T10:00:00Z',
  mode: '0644',
  linkTarget: null,
})

function renderPanel() {
  render(
    <QueryClientProvider client={new QueryClient()}>
      <ContainerFilesPanel hostId="h1" containerId="c1" />
    </QueryClientProvider>,
  )
}

describe('ContainerFilesPanel', () => {
  it('lists the root, navigates into a directory and back via breadcrumb', async () => {
    apiFetch.mockImplementation((path: string) =>
      Promise.resolve(
        path.endsWith('path=%2F')
          ? [entry('etc', 'DIRECTORY', '/etc'), entry('a.txt', 'FILE', '/a.txt')]
          : [entry('hosts', 'FILE', '/etc/hosts')],
      ),
    )
    renderPanel()
    fireEvent.click(await screen.findByRole('button', { name: 'etc/' }))
    expect(await screen.findByText('hosts')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Download hosts' })).toHaveAttribute(
      'href',
      '/api/hosts/h1/containers/c1/files/download?path=%2Fetc%2Fhosts',
    )
    fireEvent.click(screen.getByRole('button', { name: '/' }))
    expect(await screen.findByText('a.txt')).toBeInTheDocument()
  })

  it('shows a clear message when the directory is too large to list', async () => {
    apiFetch.mockRejectedValue(new ApiError(413, 'x', 'DIRECTORY_TOO_LARGE'))
    renderPanel()
    expect(await screen.findByText(/too large to list.*subdirectory/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument()
  })

  it('uploads with progress into the current directory and refreshes', async () => {
    apiFetch.mockResolvedValue([])
    const xhr = {
      open: vi.fn(),
      setRequestHeader: vi.fn(),
      send: vi.fn(),
      abort: vi.fn(),
      upload: {} as { onprogress?: (e: unknown) => void },
      status: 204,
      responseText: '',
      statusText: '',
      onload: undefined as undefined | (() => void),
    }
    vi.stubGlobal('XMLHttpRequest', function () {
      return xhr
    })
    renderPanel()
    await screen.findByText('This directory is empty.')
    const file = new File(['hi'], 'note.txt')
    fireEvent.change(screen.getByLabelText('File to upload'), { target: { files: [file] } })
    expect(xhr.open).toHaveBeenCalledWith('PUT', '/api/hosts/h1/containers/c1/files?path=%2F')
    xhr.upload.onprogress?.({ lengthComputable: true, loaded: 1, total: 2 })
    expect(await screen.findByText(/note.txt … 50%/)).toBeInTheDocument()
    xhr.onload?.()
    expect(await screen.findByText('Uploaded note.txt to /')).toBeInTheDocument()
    await waitFor(() => expect(apiFetch.mock.calls.length).toBeGreaterThan(1))
  })

  it('shows an upload error inline', async () => {
    apiFetch.mockResolvedValue([])
    const xhr = {
      open: vi.fn(),
      setRequestHeader: vi.fn(),
      send: vi.fn(),
      upload: {},
      status: 400,
      responseText: '{"code":"INVALID_FILE_NAME"}',
      statusText: '',
      onload: undefined as undefined | (() => void),
    }
    vi.stubGlobal('XMLHttpRequest', function () {
      return xhr
    })
    renderPanel()
    await screen.findByText('This directory is empty.')
    fireEvent.change(screen.getByLabelText('File to upload'), { target: { files: [new File(['x'], 'a')] } })
    xhr.onload?.()
    expect(await screen.findByText('The file name is not allowed.')).toBeInTheDocument()
  })
})
