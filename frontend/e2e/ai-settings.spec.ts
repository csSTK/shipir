import { expect, test } from './fixtures/api.ts'

const settings = {
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
const user = (role: string) => ({ username: 'u', role, setupRequired: false, setupStep: null })

test('admin saves a key and tests the connection', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', { body: user('ADMIN') })
  api.mock('GET', '/api/settings/ai', { body: settings })
  api.mock('PUT', '/api/settings/ai', { body: { ...settings, providers: [{ ...settings.providers[0], apiKeyHint: '…new1' }, settings.providers[1]] } })
  api.mock('POST', '/api/settings/ai/test', { body: { status: 'AUTH_FAILED' } })
  await page.goto('/settings/ai')

  await expect(page.getByLabel('Base URL')).toHaveValue('https://api.example.com/v1')
  await expect(page.getByLabel('API key')).toHaveValue('')
  await expect(page.getByText(/9f2c/)).toBeVisible()

  await page.getByLabel('API key').fill('sk-secret-key')
  await page.getByRole('button', { name: 'Save' }).click()
  await expect(page.getByText('AI settings saved.')).toBeVisible()
  await expect(page.getByText('sk-secret-key')).toHaveCount(0)
  await expect(page.getByLabel('API key')).toHaveValue('')
  await expect(page.getByText(/new1/)).toBeVisible()

  await page.getByRole('button', { name: 'Test connection' }).click()
  await expect(page.getByText('The provider rejected the API key.')).toBeVisible()
})

test('admin switches to Gemini, saves and tests it', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', { body: user('ADMIN') })
  api.mock('GET', '/api/settings/ai', { body: settings })
  api.mock('PUT', '/api/settings/ai', {
    body: { ...settings, activeProvider: 'GEMINI', providers: [settings.providers[0], { ...settings.providers[1], apiKeyHint: '…new2' }] },
  })
  api.mock('POST', '/api/settings/ai/test', { body: { status: 'OK' } })
  await page.goto('/settings/ai')

  await page.getByRole('radio', { name: /Google Gemini/ }).click()
  await expect(page.getByLabel('Base URL')).toHaveValue('https://generativelanguage.googleapis.com')
  await expect(page.getByText(/gem1/)).toBeVisible()
  await page.getByLabel('API key').fill('g-secret')
  await page.getByRole('button', { name: 'Save' }).click()
  await expect(page.getByText('AI settings saved.')).toBeVisible()
  await expect(page.getByText(/new2/)).toBeVisible()
  await expect(page.getByRole('radio', { name: /Google Gemini/ })).toContainText('Active')
  await page.getByRole('radio', { name: /OpenAI-compatible/ }).click()
  await expect(page.getByText(/9f2c/)).toBeVisible()

  await page.getByRole('button', { name: 'Test connection' }).click()
  await expect(page.getByText('Connection successful.')).toBeVisible()
})

test('server rejection of the base URL is shown inline', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', { body: user('ADMIN') })
  api.mock('GET', '/api/settings/ai', { body: settings })
  api.mock('PUT', '/api/settings/ai', { status: 400, body: { code: 'INVALID_BASE_URL', message: 'bad' } })
  await page.goto('/settings/ai')
  await page.getByRole('button', { name: 'Save' }).click()
  await expect(page.getByRole('alert')).toContainText('not a valid base URL')
})

test('operator cannot reach the page', async ({ page, api }) => {
  api.mock('GET', '/api/auth/me', { body: user('OPERATOR') })
  await page.goto('/settings/ai')
  await expect(page.getByText('You are not allowed to view this page.')).toBeVisible()
})
