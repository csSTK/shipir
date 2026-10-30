import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../shared/api/client.ts'
import type { ProxyInstance } from './api.ts'
import { ProxySettingsPanel } from './ProxySettingsPanel.tsx'

const apiFetch = vi.fn()
vi.mock('../../shared/api/client.ts', async (importActual) => ({
  ...(await importActual<typeof import('../../shared/api/client.ts')>()),
  apiFetch: (...args: unknown[]) => apiFetch(...args),
}))
afterEach(() => {
  cleanup()
  apiFetch.mockReset()
})

const instance = (over: Partial<ProxyInstance> = {}): ProxyInstance => ({
  type: 'TRAEFIK',
  acmeEmail: 'ops@example.com',
  defaultCertMode: 'ACME_HTTP',
  dnsProvider: 'cloudflare',
  dnsCredentialsHint: 'CF_…2f9a',
  deploymentId: 'd1',
  deploymentStatus: 'RUNNING',
  ...over,
})
let queryClient: QueryClient
function renderPanel(inst: ProxyInstance) {
  queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  apiFetch.mockResolvedValue({ instance: inst, runId: null })
  render(
    <QueryClientProvider client={queryClient}>
      <ProxySettingsPanel hostId="h1" instance={inst} />
    </QueryClientProvider>,
  )
}
const type = (el: HTMLElement, value: string) => fireEvent.change(el, { target: { value } })
const puts = () => apiFetch.mock.calls.filter(([, o]) => o?.method === 'PUT')

describe('ProxySettingsPanel', () => {
  it('shows the stored credentials only as a hint and never prefills them', () => {
    renderPanel(instance())
    expect(screen.getByText(/CF_…2f9a/)).toBeInTheDocument()
    expect(screen.getByLabelText('DNS provider')).toHaveValue('cloudflare')
    expect(screen.queryByLabelText('Variable value')).not.toBeInTheDocument()
  })

  it('hides the DNS challenge for Caddy', () => {
    renderPanel(instance({ type: 'CADDY', dnsProvider: null, dnsCredentialsHint: null }))
    expect(screen.queryByLabelText('DNS provider')).not.toBeInTheDocument()
    expect(screen.queryByRole('option', { name: /DNS challenge/ })).not.toBeInTheDocument()
  })

  it('sends only what changed and clears the credentials from the mutation cache', async () => {
    renderPanel(instance())
    type(screen.getByLabelText('Default certificate'), 'INTERNAL')
    fireEvent.click(screen.getByRole('button', { name: 'Save settings' }))
    await waitFor(() => expect(puts()).toHaveLength(1))
    expect(puts()[0]).toEqual(['/hosts/h1/proxy/managed', { method: 'PUT', body: { defaultCertMode: 'INTERNAL' } }])
  })

  it('replaces the credentials when new variables are entered', async () => {
    renderPanel(instance())
    fireEvent.click(screen.getByRole('button', { name: 'Replace credentials' }))
    type(screen.getByLabelText('Variable name'), 'CF_DNS_API_TOKEN')
    type(screen.getByLabelText('Variable value'), 'tok')
    expect(screen.getByLabelText('Variable value')).toHaveAttribute('type', 'password')
    fireEvent.click(screen.getByRole('button', { name: 'Save settings' }))
    await waitFor(() => expect(puts()).toHaveLength(1))
    expect(puts()[0][1].body).toEqual({ dnsCredentials: { CF_DNS_API_TOKEN: 'tok' } })
    await waitFor(() => expect(queryClient.getMutationCache().getAll()).toHaveLength(0))
  })

  it('can remove the stored credentials', async () => {
    renderPanel(instance())
    fireEvent.click(screen.getByRole('checkbox', { name: 'Remove stored credentials' }))
    fireEvent.click(screen.getByRole('button', { name: 'Save settings' }))
    await waitFor(() => expect(puts()).toHaveLength(1))
    expect(puts()[0][1].body).toEqual({ removeDnsCredentials: true })
  })

  it('explains that the DNS settings are still in use', async () => {
    renderPanel(instance())
    apiFetch.mockRejectedValue(new ApiError(409, 'x', 'DNS_IN_USE'))
    type(screen.getByLabelText('Default certificate'), 'INTERNAL')
    fireEvent.click(screen.getByRole('button', { name: 'Save settings' }))
    expect(await screen.findByRole('alert')).toHaveTextContent(/still use/i)
  })

  it('does not keep credential values in the mutation cache after a failed save, and keeps the input', async () => {
    renderPanel(instance())
    fireEvent.click(screen.getByRole('button', { name: 'Replace credentials' }))
    type(screen.getByLabelText('Variable name'), 'CF_DNS_API_TOKEN')
    type(screen.getByLabelText('Variable value'), 'tok-secret')
    apiFetch.mockRejectedValue(new ApiError(502, 'x', 'PROXY_SYNC_FAILED'))
    fireEvent.click(screen.getByRole('button', { name: 'Save settings' }))
    expect(await screen.findByRole('alert')).toBeInTheDocument()
    await waitFor(() => expect(JSON.stringify(queryClient.getMutationCache().getAll().map((m) => m.state.variables))).not.toContain('tok-secret'))
    expect(screen.getByLabelText('Variable value')).toHaveValue('tok-secret')
  })

  describe('DNS validation', () => {
    async function saveWith(name: string, value: string, extra?: () => void) {
      renderPanel(instance())
      fireEvent.click(screen.getByRole('button', { name: 'Replace credentials' }))
      type(screen.getByLabelText('Variable name'), name)
      type(screen.getByLabelText('Variable value'), value)
      extra?.()
      fireEvent.click(screen.getByRole('button', { name: 'Save settings' }))
    }
    it('rejects a variable name that is not upper case', async () => {
      await saveWith('cf_token', 'x')
      expect(await screen.findByRole('alert')).toHaveTextContent(/variable names/i)
      expect(puts()).toHaveLength(0)
    })
    it('rejects the reserved names', async () => {
      await saveWith('ACME_EMAIL', 'x')
      expect(await screen.findByRole('alert')).toHaveTextContent(/variable names/i)
      cleanup()
      await saveWith('DNS_PROVIDER', 'x')
      expect(await screen.findByRole('alert')).toHaveTextContent(/variable names/i)
      expect(puts()).toHaveLength(0)
    })
    it('rejects values with control characters or over 2000 characters', async () => {
      await saveWith('CF_TOKEN', 'a\u0007b')
      expect(await screen.findByRole('alert')).toHaveTextContent(/value/i)
      cleanup()
      await saveWith('CF_TOKEN', 'x'.repeat(2001))
      expect(await screen.findByRole('alert')).toHaveTextContent(/value/i)
      expect(puts()).toHaveLength(0)
    })
    it('rejects more than 10 variables', async () => {
      await saveWith('V0', 'x', () => {
        for (let i = 1; i <= 10; i++) {
          fireEvent.click(screen.getByRole('button', { name: 'Add variable' }))
          type(screen.getAllByLabelText('Variable name')[i], `V${i}`)
          type(screen.getAllByLabelText('Variable value')[i], 'x')
        }
      })
      expect(await screen.findByRole('alert')).toHaveTextContent(/at most 10/i)
      expect(puts()).toHaveLength(0)
    })
    it('rejects an invalid provider code', async () => {
      renderPanel(instance({ dnsProvider: null, dnsCredentialsHint: null }))
      type(screen.getByLabelText('DNS provider'), 'Cloud Flare')
      type(screen.getByLabelText('Variable name'), 'CF_TOKEN')
      type(screen.getByLabelText('Variable value'), 'x')
      fireEvent.click(screen.getByRole('button', { name: 'Save settings' }))
      expect(await screen.findByRole('alert')).toHaveTextContent(/provider code/i)
      expect(puts()).toHaveLength(0)
    })
    it('never sends an empty provider when the field is cleared while credentials are stored', async () => {
      renderPanel(instance())
      type(screen.getByLabelText('DNS provider'), '')
      fireEvent.click(screen.getByRole('button', { name: 'Save settings' }))
      expect(await screen.findByRole('alert')).toBeInTheDocument()
      expect(puts()).toHaveLength(0)
    })
    it('tells the user to replace the credentials first when the provider changes', async () => {
      renderPanel(instance())
      type(screen.getByLabelText('DNS provider'), 'route53')
      fireEvent.click(screen.getByRole('button', { name: 'Save settings' }))
      expect(await screen.findByRole('alert')).toHaveTextContent(/Replace credentials/)
      expect(puts()).toHaveLength(0)
    })
    it.each([
      ['INVALID_DNS_PROVIDER', /provider code/i],
      ['INVALID_DNS_CREDENTIALS', /variable names/i],
      ['PROXY_DEPLOYMENT_MISSING', /deployment/i],
    ])('explains the server answer %s', async (code, text) => {
      renderPanel(instance())
      apiFetch.mockRejectedValue(new ApiError(400, 'x', code))
      type(screen.getByLabelText('Default certificate'), 'INTERNAL')
      fireEvent.click(screen.getByRole('button', { name: 'Save settings' }))
      expect(await screen.findByRole('alert')).toHaveTextContent(text)
    })
  })
})
