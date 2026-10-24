import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { QueryClientProvider } from '@tanstack/react-query'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { queryClient } from '../../app/queryClient.ts'
import { ApiError, apiFetch } from '../../shared/api/client.ts'
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

function renderPage() {
  return render(
    <QueryClientProvider client={queryClient}>
      <AccountPage user={{ username: 'pat', role: 'OPERATOR' }} />
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
  vi.resetAllMocks()
})

describe('AccountPage', () => {
  it('shows username and role read-only', () => {
    route()
    renderPage()
    expect(screen.getByText('pat')).toBeInTheDocument()
    expect(screen.getByText('OPERATOR')).toBeInTheDocument()
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
    expect(await screen.findByText(/current password is incorrect/i)).toBeInTheDocument()
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
    expect(await screen.findByText(/must be different from the current/i)).toBeInTheDocument()
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
})
