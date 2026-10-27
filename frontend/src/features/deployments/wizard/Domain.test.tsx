import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../../shared/api/client.ts'
import { ComposeWizard } from './ComposeWizard.tsx'

const apiFetch = vi.fn()
vi.mock('../../../shared/api/client.ts', async (importActual) => ({
  ...(await importActual<typeof import('../../../shared/api/client.ts')>()),
  apiFetch: (...args: unknown[]) => apiFetch(...args),
}))

afterEach(() => {
  cleanup()
  apiFetch.mockReset()
})

function serve(proxy: unknown) {
  apiFetch.mockImplementation((path: string) => {
    if (path === '/hosts/h1/proxy') return proxy instanceof Error ? Promise.reject(proxy) : Promise.resolve(proxy)
    if (path === '/compose/proxy-templates/traefik') return Promise.resolve({ yaml: 'services:\n  traefik: {}\n' })
    if (path === '/compose/generate') return Promise.resolve({ yaml: 'services: {}\n' })
    return Promise.reject(new ApiError(404, 'x', 'NOT_FOUND'))
  })
}

function open() {
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <ComposeWizard hostId="h1" onContinue={() => {}} />
    </QueryClientProvider>,
  )
}
const group = () => screen.getByRole('group', { name: 'Service 1 domain' })

describe('wizard domain section', () => {
  it('shows the detected proxy and prefills proxy and network once a host is typed', async () => {
    serve({ proxy: 'CADDY', container: 'caddy', network: 'edge' })
    open()
    expect(await within(group()).findByText(/Caddy detected \(caddy\) on network edge/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Add port to service 1' }))
    fireEvent.change(screen.getByLabelText('Service 1 port 1 container'), { target: { value: '3000' } })
    fireEvent.change(screen.getByLabelText('Service 1 domain host'), { target: { value: 'app.example.com' } })
    expect(screen.getByLabelText('Service 1 domain proxy')).toHaveValue('CADDY')
    expect(screen.getByLabelText('Service 1 domain network')).toHaveValue('edge')
    expect(screen.getByLabelText('Service 1 domain port')).toHaveValue('3000')
  })

  it('sends the domain in the generate request', async () => {
    serve({ proxy: 'TRAEFIK', container: 'traefik', network: 'proxy' })
    open()
    await within(group()).findByText(/Traefik detected/)
    fireEvent.change(screen.getByLabelText('Service 1 name'), { target: { value: 'web' } })
    fireEvent.change(screen.getByLabelText('Service 1 image'), { target: { value: 'nginx' } })
    fireEvent.change(screen.getByLabelText('Service 1 domain host'), { target: { value: 'app.example.com' } })
    fireEvent.change(screen.getByLabelText('Service 1 domain port'), { target: { value: '80' } })
    fireEvent.click(screen.getByRole('button', { name: 'Preview YAML' }))
    await waitFor(() => expect(apiFetch).toHaveBeenCalledWith('/compose/generate', expect.anything()))
    const body = apiFetch.mock.calls.find((c) => c[0] === '/compose/generate')![1].body
    expect(body.spec.services[0].domain).toEqual({ host: 'app.example.com', port: 80, proxy: 'TRAEFIK', network: 'proxy' })
  })

  it('exposes Traefik entrypoint and cert resolver, sends them and hides them for Caddy', async () => {
    serve({ proxy: 'TRAEFIK', container: 'traefik', network: 'proxy' })
    open()
    await within(group()).findByText(/Traefik detected/)
    fireEvent.change(screen.getByLabelText('Service 1 name'), { target: { value: 'web' } })
    fireEvent.change(screen.getByLabelText('Service 1 image'), { target: { value: 'nginx' } })
    fireEvent.change(screen.getByLabelText('Service 1 domain host'), { target: { value: 'app.example.com' } })
    fireEvent.change(screen.getByLabelText('Service 1 domain port'), { target: { value: '80' } })
    fireEvent.change(screen.getByLabelText('Service 1 domain entrypoint'), { target: { value: 'https' } })
    fireEvent.change(screen.getByLabelText('Service 1 domain cert resolver'), { target: { value: 'le' } })
    fireEvent.click(screen.getByRole('button', { name: 'Preview YAML' }))
    await waitFor(() => expect(apiFetch).toHaveBeenCalledWith('/compose/generate', expect.anything()))
    const body = apiFetch.mock.calls.find((c) => c[0] === '/compose/generate')![1].body
    expect(body.spec.services[0].domain).toEqual({ host: 'app.example.com', port: 80, proxy: 'TRAEFIK', network: 'proxy', entrypoint: 'https', certResolver: 'le' })
    fireEvent.change(screen.getByLabelText('Service 1 domain proxy'), { target: { value: 'CADDY' } })
    expect(screen.queryByLabelText('Service 1 domain entrypoint')).not.toBeInTheDocument()
  })

  it('shows a field error for an invalid domain and does not call generate', async () => {
    serve({ proxy: null, container: null, network: null })
    open()
    fireEvent.change(screen.getByLabelText('Service 1 name'), { target: { value: 'web' } })
    fireEvent.change(screen.getByLabelText('Service 1 image'), { target: { value: 'nginx' } })
    fireEvent.change(screen.getByLabelText('Service 1 domain host'), { target: { value: 'Bad_Host' } })
    fireEvent.click(screen.getByRole('button', { name: 'Preview YAML' }))
    expect(await screen.findByText(/lowercase domain name/)).toBeInTheDocument()
    expect(apiFetch).not.toHaveBeenCalledWith('/compose/generate', expect.anything())
  })

  it('offers the Traefik template when no proxy is detected and copies it', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })
    serve({ proxy: null, container: null, network: null })
    open()
    expect(await within(group()).findByText(/No reverse proxy detected/)).toBeInTheDocument()
    fireEvent.click(within(group()).getByRole('button', { name: 'Copy Traefik template' }))
    await waitFor(() => expect(writeText).toHaveBeenCalledWith('services:\n  traefik: {}\n'))
    expect(await within(group()).findByText('Copied')).toBeInTheDocument()
  })

  it('still works when detection fails', async () => {
    serve(new ApiError(500, 'boom', 'X'))
    open()
    expect(screen.getByLabelText('Service 1 domain host')).toBeInTheDocument()
    await waitFor(() => expect(apiFetch).toHaveBeenCalledWith('/hosts/h1/proxy'))
    expect(within(group()).queryByText(/detected/)).not.toBeInTheDocument()
  })

  it('reports a failed copy when the clipboard is unavailable', async () => {
    Object.defineProperty(navigator, 'clipboard', { value: undefined, configurable: true })
    serve({ proxy: null, container: null, network: null })
    open()
    fireEvent.click(await within(group()).findByRole('button', { name: 'Copy Traefik template' }))
    expect(await within(group()).findByText('Could not copy the template.')).toBeInTheDocument()
  })

  it('keeps a proxy the user picked before typing the host', async () => {
    serve({ proxy: 'CADDY', container: 'caddy', network: 'edge' })
    open()
    await within(group()).findByText(/Caddy detected/)
    fireEvent.change(screen.getByLabelText('Service 1 domain proxy'), { target: { value: 'TRAEFIK' } })
    fireEvent.change(screen.getByLabelText('Service 1 domain host'), { target: { value: 'app.example.com' } })
    expect(screen.getByLabelText('Service 1 domain proxy')).toHaveValue('TRAEFIK')
    expect(screen.getByLabelText('Service 1 domain network')).toHaveValue('edge')
  })

  it('does not restore a network the user cleared when the host is retyped', async () => {
    serve({ proxy: 'CADDY', container: 'caddy', network: 'edge' })
    open()
    await within(group()).findByText(/Caddy detected/)
    const host = screen.getByLabelText('Service 1 domain host')
    fireEvent.change(host, { target: { value: 'app.example.com' } })
    fireEvent.change(screen.getByLabelText('Service 1 domain network'), { target: { value: '' } })
    fireEvent.change(host, { target: { value: '' } })
    fireEvent.change(host, { target: { value: 'other.example.com' } })
    expect(screen.getByLabelText('Service 1 domain network')).toHaveValue('')
  })

  it('does not request proxy detection while the wizard is inactive', () => {
    serve({ proxy: null, container: null, network: null })
    render(
      <QueryClientProvider client={new QueryClient()}>
        <ComposeWizard hostId="h1" active={false} onContinue={() => {}} />
      </QueryClientProvider>,
    )
    expect(apiFetch).not.toHaveBeenCalledWith('/hosts/h1/proxy')
  })
})
