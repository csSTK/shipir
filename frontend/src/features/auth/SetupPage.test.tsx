import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { QueryClientProvider } from '@tanstack/react-query'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { queryClient } from '../../app/queryClient.ts'
import { ApiError, apiFetch } from '../../shared/api/client.ts'
import { SetupPage } from './SetupPage.tsx'
import { useCurrentUser } from './api.ts'

vi.mock('../../shared/api/client.ts', async (importActual) => ({
  ...(await importActual<typeof import('../../shared/api/client.ts')>()),
  apiFetch: vi.fn(),
}))
const apiFetchMock = vi.mocked(apiFetch)

const GOOD = 'Abcdefghijklm1!'

function renderPage() {
  return render(
    <QueryClientProvider client={queryClient}>
      <SetupPage username="admin" />
    </QueryClientProvider>,
  )
}
const type = (label: string, value: string) =>
  fireEvent.change(screen.getByLabelText(label), { target: { value } })
const submit = () => screen.getByRole('button', { name: /set password/i })

function fillValid() {
  type('Current password', 'initial')
  type('New password', GOOD)
  type('Confirm new password', GOOD)
}

beforeEach(() => queryClient.clear())
afterEach(() => {
  cleanup()
  vi.resetAllMocks()
})

describe('SetupPage', () => {
  it('explains why the step exists', () => {
    renderPage()
    expect(screen.getByText(/first start/i)).toBeInTheDocument()
  })

  it('updates the checklist while typing', () => {
    renderPage()
    const rule = (name: RegExp) => screen.getByText(name).closest('li')
    expect(rule(/15 to 128/i)).toHaveAttribute('data-met', 'false')

    type('New password', 'Abcdefghijklm1!')
    expect(rule(/15 to 128/i)).toHaveAttribute('data-met', 'true')
    expect(rule(/uppercase/i)).toHaveAttribute('data-met', 'true')
    expect(rule(/matches/i)).toHaveAttribute('data-met', 'false')

    type('New password', 'abc')
    expect(rule(/uppercase/i)).toHaveAttribute('data-met', 'false')
    expect(rule(/15 to 128/i)).toHaveAttribute('data-met', 'false')
  })

  it('keeps submit disabled until all rules pass and a current password is given', () => {
    renderPage()
    expect(submit()).toBeDisabled()
    type('New password', GOOD)
    type('Confirm new password', GOOD)
    expect(submit()).toBeDisabled() // no current password
    type('Current password', 'initial')
    expect(submit()).toBeEnabled()
  })

  it('blocks submit on a mismatched confirmation', () => {
    renderPage()
    fillValid()
    type('Confirm new password', GOOD + 'x')
    expect(submit()).toBeDisabled()
    fireEvent.submit(submit().closest('form')!)
    expect(apiFetchMock).not.toHaveBeenCalled()
  })

  it('posts the passwords and confirms on success', async () => {
    apiFetchMock.mockResolvedValue(undefined as never)
    renderPage()
    fillValid()
    fireEvent.click(submit())

    await waitFor(() =>
      expect(apiFetchMock).toHaveBeenCalledWith('/auth/password', {
        method: 'POST',
        body: { currentPassword: 'initial', newPassword: GOOD },
      }),
    )
    expect(await screen.findByRole('status')).toHaveTextContent(/password (set|updated|changed)/i)
  })

  it('invalidates /me after success so the gate re-evaluates', async () => {
    apiFetchMock.mockResolvedValue(undefined as never)
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries')
    renderPage()
    fillValid()
    fireEvent.click(submit())
    await waitFor(() => expect(invalidate).toHaveBeenCalledWith({ queryKey: ['auth', 'me'] }))
  })

  it('shows readable messages for server PASSWORD_POLICY violations', async () => {
    apiFetchMock.mockRejectedValue(
      new ApiError(400, '{}', 'PASSWORD_POLICY', ['SAME_AS_CURRENT', 'MISSING_SYMBOL']),
    )
    renderPage()
    fillValid()
    fireEvent.click(submit())

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(/different from the current/i)
    expect(alert).toHaveTextContent(/symbol/i)
    const next = screen.getByLabelText('New password')
    expect(next).toHaveAttribute('aria-describedby', alert.id)
    expect(next).toHaveAttribute('aria-invalid', 'true')
    expect(screen.getByLabelText('Current password')).not.toHaveAttribute('aria-invalid')
  })

  it('shows a message for INVALID_CURRENT_PASSWORD and focuses the field', async () => {
    apiFetchMock.mockRejectedValue(new ApiError(400, '{}', 'INVALID_CURRENT_PASSWORD'))
    renderPage()
    fillValid()
    fireEvent.click(submit())

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(/current password is (incorrect|wrong)/i)
    const current = screen.getByLabelText('Current password')
    expect(current).toHaveAttribute('aria-describedby', alert.id)
    expect(current).toHaveAttribute('aria-invalid', 'true')
    expect(screen.getByLabelText('New password')).not.toHaveAttribute('aria-invalid')
    await waitFor(() => expect(screen.getByLabelText('Current password')).toHaveFocus())
  })

  it('shows a generic message on other failures', async () => {
    apiFetchMock.mockRejectedValue(new ApiError(500, 'boom'))
    renderPage()
    fillValid()
    fireEvent.click(submit())
    expect(await screen.findByRole('alert')).toHaveTextContent(/try again/i)
  })
})

describe('SetupPage steps', () => {
  const stepIndicator = () => screen.getByRole('list', { name: /setup steps/i })

  it('renders the password step with a step indicator by default', () => {
    renderPage()
    expect(screen.getByRole('heading', { name: 'Set your password' })).toBeInTheDocument()
    expect(stepIndicator()).toHaveTextContent(/password/i)
    expect(stepIndicator()).toHaveTextContent(/environment/i)
    expect(screen.getByText(/password/i, { selector: '[aria-current="step"]' })).toBeInTheDocument()
  })

  it('renders the environment step when step is ENVIRONMENT', () => {
    render(
      <QueryClientProvider client={queryClient}>
        <SetupPage username="admin" step="ENVIRONMENT" />
      </QueryClientProvider>,
    )
    expect(screen.getByRole('heading', { name: /add your first environment/i })).toBeInTheDocument()
    expect(screen.queryByLabelText('Current password')).not.toBeInTheDocument()
    expect(screen.getByText(/environment/i, { selector: '[aria-current="step"]' })).toBeInTheDocument()
    expect(screen.getByRole('radio', { name: /local docker socket/i })).toBeInTheDocument()
  })

  it('creates the environment and refetches /auth/me', async () => {
    let me = { username: 'admin', role: 'ADMIN', setupRequired: true, setupStep: 'ENVIRONMENT' }
    apiFetchMock.mockImplementation(async (path: string) => {
      if (path === '/auth/me') return me as never
      if (path === '/hosts') {
        me = { ...me, setupRequired: false, setupStep: null as never }
        return { id: '1', name: 'Local', connectionType: 'LOCAL' } as never
      }
      throw new Error(`unexpected ${path}`)
    })
    function Harness() {
      const { data } = useCurrentUser()
      if (!data) return null
      return data.setupStep ? (
        <SetupPage username={data.username} step={data.setupStep} />
      ) : (
        <p>App</p>
      )
    }
    render(
      <QueryClientProvider client={queryClient}>
        <Harness />
      </QueryClientProvider>,
    )
    fireEvent.change(await screen.findByLabelText('Name'), { target: { value: 'Local' } })
    fireEvent.click(screen.getByRole('button', { name: /continue|add environment|finish/i }))

    await waitFor(() =>
      expect(apiFetchMock).toHaveBeenCalledWith('/hosts', {
        method: 'POST',
        body: { name: 'Local', connectionType: 'LOCAL' },
      }),
    )
    expect(await screen.findByText('App')).toBeInTheDocument()
    expect(apiFetchMock.mock.calls.filter(([path]) => path === '/auth/me').length).toBeGreaterThan(1)
  })

  it('shows a connection failure and stays on the environment step', async () => {
    apiFetchMock.mockRejectedValue(new ApiError(400, '{}', 'CONNECTION_FAILED', undefined, ['UNREACHABLE']))
    render(
      <QueryClientProvider client={queryClient}>
        <SetupPage username="admin" step="ENVIRONMENT" />
      </QueryClientProvider>,
    )
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Local' } })
    fireEvent.click(screen.getByRole('button', { name: /continue|add environment|finish/i }))
    expect(await screen.findByRole('alert')).toHaveTextContent(/could not reach/i)
    expect(screen.getByRole('heading', { name: /add your first environment/i })).toBeInTheDocument()
  })
})
