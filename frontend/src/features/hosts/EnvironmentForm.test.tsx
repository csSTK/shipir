import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, apiFetch } from '../../shared/api/client.ts'
import { EnvironmentForm } from './EnvironmentForm.tsx'

vi.mock('../../shared/api/client.ts', async (importActual) => ({
  ...(await importActual<typeof import('../../shared/api/client.ts')>()),
  apiFetch: vi.fn(),
}))
const apiFetchMock = vi.mocked(apiFetch)

afterEach(() => {
  cleanup()
  vi.resetAllMocks()
})

const HOST = {
  id: '1',
  name: 'Local',
  connectionType: 'LOCAL',
  host: null,
  port: null,
  tls: false,
  createdAt: '2026-01-01T00:00:00Z',
}

function renderForm(props: { onCreated?: (h: unknown) => void; submitLabel?: string } = {}) {
  const client = new QueryClient({ defaultOptions: { mutations: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <EnvironmentForm {...props} />
    </QueryClientProvider>,
  )
}
const type = (label: string | RegExp, value: string) =>
  fireEvent.change(screen.getByLabelText(label), { target: { value } })
const pickFile = (label: string | RegExp, content: string, name: string) =>
  fireEvent.change(screen.getByLabelText(label), {
    target: { files: [new File([content], name, { type: 'application/x-pem-file' })] },
  })
const button = (name: RegExp) => screen.getByRole('button', { name })

async function fillTcp() {
  fireEvent.click(screen.getByRole('radio', { name: /remote/i }))
  type('Name', 'Prod')
  type('Host', 'docker.example.com')
  type('Port', '2376')
  pickFile(/CA certificate/i, 'CA-SECRET-PEM', 'ca.pem')
  pickFile(/Client certificate/i, 'CERT-SECRET-PEM', 'cert.pem')
  pickFile(/Client key/i, 'KEY-SECRET-PEM', 'key.pem')
  await screen.findAllByText(/key\.pem/)
}

describe('EnvironmentForm', () => {
  it('local mode submits only name and connectionType', async () => {
    apiFetchMock.mockResolvedValue(HOST)
    const onCreated = vi.fn()
    renderForm({ onCreated, submitLabel: 'Add environment' })

    expect(button(/add environment/i)).toBeDisabled()
    type('Name', 'Local')
    fireEvent.click(button(/add environment/i))

    await waitFor(() => expect(onCreated).toHaveBeenCalledWith(HOST))
    expect(apiFetchMock).toHaveBeenCalledWith('/hosts', {
      method: 'POST',
      body: { name: 'Local', connectionType: 'LOCAL' },
    })
  })

  it('TCP mode keeps submit disabled until host, port and all three files are set', async () => {
    renderForm()
    fireEvent.click(screen.getByRole('radio', { name: /remote/i }))
    type('Name', 'Prod')
    expect(button(/^add environment$/i)).toBeDisabled()
    type('Host', 'docker.example.com')
    type('Port', '2376')
    pickFile(/CA certificate/i, 'ca', 'ca.pem')
    pickFile(/Client certificate/i, 'cert', 'cert.pem')
    await screen.findByText('ca.pem')
    expect(button(/^add environment$/i)).toBeDisabled()
    pickFile(/Client key/i, 'key', 'key.pem')
    await waitFor(() => expect(button(/^add environment$/i)).toBeEnabled())
  })

  it('TCP mode sends the PEM contents but never renders them', async () => {
    apiFetchMock.mockResolvedValue({ ...HOST, connectionType: 'TCP' })
    const { container } = renderForm()
    await fillTcp()

    expect(container.textContent).not.toContain('SECRET-PEM')
    fireEvent.click(button(/^add environment$/i))

    await waitFor(() => expect(apiFetchMock).toHaveBeenCalled())
    expect(apiFetchMock).toHaveBeenCalledWith('/hosts', {
      method: 'POST',
      body: {
        name: 'Prod',
        connectionType: 'TCP',
        host: 'docker.example.com',
        port: 2376,
        tlsCaPem: 'CA-SECRET-PEM',
        tlsCertPem: 'CERT-SECRET-PEM',
        tlsKeyPem: 'KEY-SECRET-PEM',
      },
    })
    expect(container.textContent).not.toContain('SECRET-PEM')
  })

  it('shows the Docker version after a successful connection test', async () => {
    apiFetchMock.mockResolvedValue({ ok: true, dockerVersion: '27.1.2', errorCode: null, message: null })
    renderForm()
    fireEvent.click(button(/test connection/i))

    expect(await screen.findByText(/27\.1\.2/)).toBeInTheDocument()
    expect(apiFetchMock).toHaveBeenCalledWith('/hosts/connection-test', {
      method: 'POST',
      body: { connectionType: 'LOCAL' },
    })
  })

  it.each([
    ['UNREACHABLE', /could not reach/i],
    ['TIMEOUT', /timed out/i],
    ['TLS_ERROR', /tls handshake/i],
    ['PERMISSION_DENIED', /permission/i],
    ['INVALID_TLS_MATERIAL', /certificate or key/i],
  ])('shows a human message for %s', async (errorCode, expected) => {
    apiFetchMock.mockResolvedValue({ ok: false, dockerVersion: null, errorCode, message: 'raw' })
    renderForm()
    fireEvent.click(button(/test connection/i))
    expect(await screen.findByText(expected)).toBeInTheDocument()
  })

  it('shows INVALID_TLS_MATERIAL returned as 400 for the connection test', async () => {
    apiFetchMock.mockRejectedValue(new ApiError(400, '{}', 'INVALID_TLS_MATERIAL'))
    renderForm()
    fireEvent.click(button(/test connection/i))
    expect(await screen.findByText(/certificate or key/i)).toBeInTheDocument()
  })

  it.each([
    [new ApiError(409, '', 'HOST_NAME_TAKEN'), /name is already in use/i],
    [new ApiError(409, '', 'LOCAL_HOST_EXISTS'), /local docker environment already exists/i],
    [new ApiError(409, '', 'ENCRYPTION_KEY_MISSING'), /encryption key/i],
    [new ApiError(422, '', 'CONNECTION_FAILED', undefined, ['TIMEOUT', 'x']), /timed out/i],
    [new ApiError(400, '', 'VALIDATION_ERROR', undefined, ['port: must be between 1 and 65535']), /port: must be between/i],
    [new ApiError(500, '', undefined), /could not add/i],
  ])('maps server error %# inline on submit', async (error, expected) => {
    apiFetchMock.mockRejectedValue(error)
    renderForm()
    type('Name', 'Local')
    fireEvent.click(button(/^add environment$/i))
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent(expected))
  })

  it('switching back to Local hides TCP fields', () => {
    renderForm()
    fireEvent.click(screen.getByRole('radio', { name: /remote/i }))
    expect(screen.getByLabelText('Host')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('radio', { name: /local/i }))
    expect(screen.queryByLabelText('Host')).not.toBeInTheDocument()
  })
})
