import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, apiFetch } from '../../shared/api/client.ts'
import { AiSettingsPage } from './AiSettingsPage.tsx'

vi.mock('../../shared/api/client.ts', async (importActual) => ({
  ...(await importActual<typeof import('../../shared/api/client.ts')>()),
  apiFetch: vi.fn(),
}))
const apiFetchMock = vi.mocked(apiFetch)

afterEach(() => {
  cleanup()
  vi.resetAllMocks()
})

const current = {
  activeProvider: 'OPENAI_COMPATIBLE',
  enabled: true,
  providers: [
    {
      provider: 'OPENAI_COMPATIBLE',
      baseUrl: 'https://api.example.com/v1',
      model: 'gpt-x',
      keyConfigured: true,
      apiKeyHint: '…9f2c',
    },
    {
      provider: 'GEMINI',
      baseUrl: 'https://generativelanguage.googleapis.com',
      model: 'gemini-x',
      keyConfigured: true,
      apiKeyHint: '…gem1',
    },
  ],
}
const openai = current.providers[0]

function renderPage(handler?: (path: string, method: string, body: unknown) => unknown, settings = current) {
  apiFetchMock.mockImplementation((path: string, options?: { method?: string; body?: unknown }) => {
    const method = options?.method ?? 'GET'
    if (method === 'GET' && path === '/settings/ai') return Promise.resolve(settings)
    const result = handler?.(path, method, options?.body)
    return result instanceof Error ? Promise.reject(result) : Promise.resolve(result)
  })
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <AiSettingsPage />
    </QueryClientProvider>,
  )
}

const calls = (method: string) =>
  apiFetchMock.mock.calls.filter(([, o]) => (o as { method?: string } | undefined)?.method === method)

describe('AiSettingsPage', () => {
  it('loads the settings and never prefills the key', async () => {
    renderPage()
    expect(await screen.findByLabelText('Base URL')).toHaveValue('https://api.example.com/v1')
    expect(screen.getByLabelText('Model')).toHaveValue('gpt-x')
    expect(screen.getByRole('switch', { name: 'Enable assistant' })).toBeChecked()
    expect(screen.getByLabelText('API key')).toHaveValue('')
    expect(screen.getByText(/9f2c/)).toBeInTheDocument()
  })

  it('keeps the stored key when the field is empty (apiKey omitted)', async () => {
    renderPage(() => current)
    fireEvent.change(await screen.findByLabelText('Model'), { target: { value: 'gpt-y' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    expect(await screen.findByText('AI settings saved.')).toBeInTheDocument()
    expect(calls('PUT')[0]).toEqual([
      '/settings/ai',
      {
        method: 'PUT',
        body: {
          enabled: true,
          activeProvider: 'OPENAI_COMPATIBLE',
          provider: 'OPENAI_COMPATIBLE',
          baseUrl: openai.baseUrl,
          model: 'gpt-y',
        },
      },
    ])
  })

  it('sends a new key and clears the field afterwards', async () => {
    renderPage(() => current)
    const key = await screen.findByLabelText('API key')
    fireEvent.change(key, { target: { value: 'sk-new' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    await screen.findByText('AI settings saved.')
    expect((calls('PUT')[0][1] as { body: Record<string, unknown> }).body.apiKey).toBe('sk-new')
    expect(key).toHaveValue('')
  })

  it('clears the key with an empty string after the toggle', async () => {
    renderPage(() => current)
    await screen.findByLabelText('Base URL')
    fireEvent.click(screen.getByRole('button', { name: 'Remove stored key' }))
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    await screen.findByText('AI settings saved.')
    expect((calls('PUT')[0][1] as { body: Record<string, unknown> }).body.apiKey).toBe('')
  })

  it.each([
    ['OK', 'Connection successful.'],
    ['AUTH_FAILED', 'The provider rejected the API key.'],
    ['UNREACHABLE', 'The provider could not be reached.'],
  ])('shows test status %s', async (status, text) => {
    renderPage(() => ({ status }))
    fireEvent.click(await screen.findByRole('button', { name: 'Test connection' }))
    expect(await screen.findByText(text)).toBeInTheDocument()
    await waitFor(() => expect(calls('POST')[0]).toEqual(['/settings/ai/test', { method: 'POST', body: { provider: 'OPENAI_COMPATIBLE' } }]))
  })

  it('maps API_KEY_REQUIRED on test', async () => {
    renderPage(() => new ApiError(400, 'x', 'API_KEY_REQUIRED'))
    fireEvent.click(await screen.findByRole('button', { name: 'Test connection' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Save an API key first')
  })

  it.each([
    ['INVALID_BASE_URL', 400, 'not a valid'],
    ['ENCRYPTION_KEY_MISSING', 409, 'SHIPIR_ENCRYPTION_KEY'],
    ['SECRET_REQUIRED_FOR_NEW_ENDPOINT', 400, 'enter the API key again'],
  ])('shows server error %s inline', async (code, status, text) => {
    renderPage(() => new ApiError(status, 'x', code))
    await screen.findByLabelText('Base URL')
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    expect(await screen.findByRole('alert')).toHaveTextContent(text)
    expect(screen.queryByText('AI settings saved.')).not.toBeInTheDocument()
  })

  it('requires base URL and model client-side', async () => {
    renderPage()
    fireEvent.change(await screen.findByLabelText('Model'), { target: { value: ' ' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    expect(await screen.findByRole('alert')).toBeInTheDocument()
    expect(calls('PUT')).toHaveLength(0)
  })

  it('switches provider, keeps drafts and shows each key hint', async () => {
    renderPage()
    await screen.findByLabelText('Base URL')
    expect(screen.getByRole('radio', { name: /OpenAI-compatible/ })).toBeChecked()
    fireEvent.change(screen.getByLabelText('Model'), { target: { value: 'gpt-draft' } })
    fireEvent.click(screen.getByRole('radio', { name: /Google Gemini/ }))
    expect(screen.getByLabelText('Base URL')).toHaveValue('https://generativelanguage.googleapis.com')
    expect(screen.getByLabelText('Model')).toHaveValue('gemini-x')
    expect(screen.getByText(/gem1/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('radio', { name: /OpenAI-compatible/ }))
    expect(screen.getByLabelText('Model')).toHaveValue('gpt-draft')
    expect(screen.getByText(/9f2c/)).toBeInTheDocument()
  })

  it('saves the selected provider as the edited and active one', async () => {
    renderPage(() => current)
    fireEvent.click(await screen.findByRole('radio', { name: /Google Gemini/ }))
    fireEvent.change(screen.getByLabelText('API key'), { target: { value: 'g-key' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    await screen.findByText('AI settings saved.')
    expect((calls('PUT')[0][1] as { body: unknown }).body).toEqual({
      enabled: true,
      activeProvider: 'GEMINI',
      provider: 'GEMINI',
      baseUrl: 'https://generativelanguage.googleapis.com',
      model: 'gemini-x',
      apiKey: 'g-key',
    })
  })

  it('tests the selected provider', async () => {
    renderPage(() => ({ status: 'OK' }))
    fireEvent.click(await screen.findByRole('radio', { name: /Google Gemini/ }))
    fireEvent.click(screen.getByRole('button', { name: 'Test connection' }))
    await screen.findByText('Connection successful.')
    expect(calls('POST')[0]).toEqual(['/settings/ai/test', { method: 'POST', body: { provider: 'GEMINI' } }])
  })
})
