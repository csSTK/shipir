import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { QueryClientProvider } from '@tanstack/react-query'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { queryClient } from '../../app/queryClient.ts'
import { ApiError, apiFetch } from '../../shared/api/client.ts'
import { applyLanguage, i18n } from '../../shared/i18n/index.ts'
import { AccountPage } from './AccountPage.tsx'

vi.mock('../../shared/api/client.ts', async (importActual) => ({
  ...(await importActual<typeof import('../../shared/api/client.ts')>()),
  apiFetch: vi.fn(),
}))
const apiFetchMock = vi.mocked(apiFetch)

const GOOD = 'Abcdefghijklm1!'
const PREFS = [
  { type: 'HOST_DOWN', inAppEnabled: true },
  { type: 'HOST_UP', inAppEnabled: false },
  { type: 'DEPLOYMENT_FAILED', inAppEnabled: true },
  { type: 'CONTAINER_CRASHED', inAppEnabled: true },
  { type: 'CONTAINER_UNHEALTHY', inAppEnabled: true },
  { type: 'GIT_AUTO_UPDATE_FAILED', inAppEnabled: true },
]

function route(overrides: Record<string, () => unknown> = {}) {
  apiFetchMock.mockImplementation(((path: string, init?: { method?: string }) => {
    const key = `${init?.method ?? 'GET'} ${path}`
    if (overrides[key]) return Promise.resolve().then(overrides[key])
    if (key === 'GET /notifications/preferences') return Promise.resolve(PREFS)
    if (key === 'PUT /notifications/preferences') return Promise.resolve(undefined)
    return Promise.resolve(undefined)
  }) as typeof apiFetch)
}

function renderPage(externalIssuer: string | null = null, locale: string | null = null) {
  return render(
    <QueryClientProvider client={queryClient}>
      <AccountPage user={{ username: 'pat', role: 'OPERATOR', externalIssuer, locale }} />
    </QueryClientProvider>,
  )
}
const type = (label: string, value: string) =>
  fireEvent.change(screen.getByLabelText(label), { target: { value } })
function fillValid() {
  type('Current password', 'old')
  type('New password', GOOD)
  type('Confirm new password', GOOD)
}
const submit = () => screen.getByRole('button', { name: /change password/i })

beforeEach(() => queryClient.clear())
afterEach(() => {
  cleanup()
  applyLanguage('en')
  vi.resetAllMocks()
})

describe('AccountPage', () => {
  it('shows the linked identity only for single sign-on users', () => {
    route()
    renderPage('https://id.example.com')
    expect(screen.getByText('Linked to https://id.example.com')).toBeInTheDocument()
    cleanup()
    renderPage(null)
    expect(screen.queryByText(/Linked to/)).not.toBeInTheDocument()
  })

  it('shows username and role read-only', () => {
    route()
    renderPage()
    expect(screen.getByText('pat')).toBeInTheDocument()
    expect(screen.getByText('OPERATOR')).toBeInTheDocument()
  })

  it('marks each password rule with a 16px icon hidden from assistive tech and states it as text', () => {
    route()
    renderPage()
    const li = screen.getByText(/uppercase/i).closest('li') as HTMLElement
    const icon = li.querySelector('svg')
    expect(icon).toHaveAttribute('aria-hidden', 'true')
    expect(icon).toHaveAttribute('width', '16')
    expect(within(li).getByText('Not met')).toBeInTheDocument()
    expect(li.textContent).not.toMatch(/[\u2713\u25CB]/)

    type('New password', GOOD)
    expect(within(li).getByText('Met')).toBeInTheDocument()
  })

  it('changes the password and shows a success notice', async () => {
    route()
    renderPage()
    expect(submit()).toBeDisabled()
    fillValid()
    fireEvent.click(submit())
    await screen.findByText(/password changed/i)
    expect(apiFetchMock).toHaveBeenCalledWith('/auth/password', {
      method: 'POST',
      body: { currentPassword: 'old', newPassword: GOOD },
    })
  })

  it('shows a wrong current password inline', async () => {
    route({
      'POST /auth/password': () => {
        throw new ApiError(400, 'bad', 'INVALID_CURRENT_PASSWORD')
      },
    })
    renderPage()
    fillValid()
    fireEvent.click(submit())
    const error = await screen.findByText(/current password is incorrect/i)
    const current = screen.getByLabelText('Current password')
    expect(current).toHaveAttribute('aria-describedby', error.closest('[role="alert"]')?.id)
    expect(current).toHaveAttribute('aria-invalid', 'true')
    expect(screen.getByLabelText('New password')).not.toHaveAttribute('aria-invalid')
  })

  it('shows server policy violations inline', async () => {
    route({
      'POST /auth/password': () => {
        throw new ApiError(400, 'bad', 'PASSWORD_POLICY', ['SAME_AS_CURRENT'])
      },
    })
    renderPage()
    fillValid()
    fireEvent.click(submit())
    const error = await screen.findByText(/must be different from the current/i)
    const next = screen.getByLabelText('New password')
    expect(next).toHaveAttribute('aria-describedby', error.closest('[role="alert"]')?.id)
    expect(next).toHaveAttribute('aria-invalid', 'true')
    expect(screen.getByLabelText('Current password')).not.toHaveAttribute('aria-invalid')
  })

  it('loads preferences and saves a toggle', async () => {
    route()
    renderPage()
    const down = await screen.findByRole('switch', { name: /host down/i })
    const up = screen.getByRole('switch', { name: /host up/i })
    expect(down).toBeChecked()
    expect(up).not.toBeChecked()
    fireEvent.click(up)
    await waitFor(() =>
      expect(apiFetchMock).toHaveBeenCalledWith('/notifications/preferences', {
        method: 'PUT',
        body: PREFS.map((p) => (p.type === 'HOST_UP' ? { ...p, inAppEnabled: true } : p)),
      }),
    )
  })

  it('offers a switch for every notification type', async () => {
    route()
    renderPage()
    expect(await screen.findByRole('switch', { name: 'Deployment failed' })).toBeChecked()
    expect(screen.getByRole('switch', { name: 'Container crashed' })).toBeChecked()
    expect(screen.getByRole('switch', { name: 'Container unhealthy' })).toBeChecked()
    expect(screen.getByRole('switch', { name: 'Git auto-update failed' })).toBeChecked()
    expect(screen.getAllByRole('switch')).toHaveLength(6)
  })

  it('saves a switch for a newly added type', async () => {
    route()
    renderPage()
    fireEvent.click(await screen.findByRole('switch', { name: 'Container unhealthy' }))
    await waitFor(() =>
      expect(apiFetchMock).toHaveBeenCalledWith('/notifications/preferences', {
        method: 'PUT',
        body: PREFS.map((p) => (p.type === 'CONTAINER_UNHEALTHY' ? { ...p, inAppEnabled: false } : p)),
      }),
    )
  })

  it('shows an empty state instead of an empty card when there are no notification preferences', async () => {
    route({ 'GET /notifications/preferences': () => [] })
    renderPage()
    const region = await screen.findByRole('region', { name: 'Notifications' })
    expect(await within(region).findByText(/no notification settings/i)).toBeInTheDocument()
    expect(within(region).queryByRole('switch')).toBeNull()
  })

  it('lists the password rules as a spaced checklist', () => {
    route()
    renderPage()
    const list = document.querySelector('.account__rules')
    expect(list?.querySelectorAll('li').length).toBeGreaterThan(0)
    expect(list?.querySelector('li')?.className).toContain('account__rule')
  })

  it('shows an error when preferences cannot be loaded', async () => {
    route({
      'GET /notifications/preferences': () => {
        throw new ApiError(500, 'x')
      },
    })
    renderPage()
    expect(
      await screen.findByText(/could not load notification preferences/i, {}, { timeout: 4000 }),
    ).toBeInTheDocument()
  })

  it('shows an error when saving a preference fails', async () => {
    route({
      'PUT /notifications/preferences': () => {
        throw new ApiError(500, 'x')
      },
    })
    renderPage()
    fireEvent.click(await screen.findByRole('switch', { name: /host up/i }))
    expect(await screen.findByText(/could not save/i)).toBeInTheDocument()
  })

  it('shows the chosen language and defaults to automatic', () => {
    route()
    renderPage(null, 'de')
    expect(screen.getByLabelText('Language')).toHaveValue('de')
    cleanup()
    renderPage(null, null)
    expect(screen.getByLabelText('Language')).toHaveValue('')
  })

  it('saves the language choice and switches the UI immediately', async () => {
    route()
    renderPage()
    fireEvent.change(screen.getByLabelText('Language'), { target: { value: 'de' } })
    await waitFor(() => expect(i18n.language).toBe('de'))
    expect(apiFetchMock).toHaveBeenCalledWith('/auth/me/locale', { method: 'PUT', body: { locale: 'de' } })
  })

  it('clears the stored language when automatic is chosen', async () => {
    route()
    renderPage(null, 'de')
    fireEvent.change(screen.getByLabelText('Language'), { target: { value: '' } })
    await waitFor(() => expect(apiFetchMock).toHaveBeenCalledWith('/auth/me/locale', { method: 'PUT', body: { locale: null } }))
  })

  it('keeps the language when saving fails and says so', async () => {
    route({
      'PUT /auth/me/locale': () => {
        throw new ApiError(500, 'x')
      },
    })
    renderPage()
    fireEvent.change(screen.getByLabelText('Language'), { target: { value: 'de' } })
    expect(await screen.findByText(/could not save the language/i)).toBeInTheDocument()
    expect(i18n.language).toBe('en')
  })
})
