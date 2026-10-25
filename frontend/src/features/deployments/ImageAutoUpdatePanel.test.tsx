import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../shared/api/client.ts'
import { ImageAutoUpdatePanel } from './ImageAutoUpdatePanel.tsx'
import type { ImageAutoUpdateInfo } from './api.ts'

const apiFetch = vi.fn()
vi.mock('../../shared/api/client.ts', async (importActual) => ({
  ...(await importActual<typeof import('../../shared/api/client.ts')>()),
  apiFetch: (...args: unknown[]) => apiFetch(...args),
}))

afterEach(() => {
  cleanup()
  apiFetch.mockReset()
})

const URL = '/hosts/h1/managed-deployments/s1/image-auto-update'
const off: ImageAutoUpdateInfo = { enabled: false, windowStart: null, windowEnd: null, lastAttemptAt: null, lastResult: null }

function renderPanel(info: ImageAutoUpdateInfo = off, canWrite = true) {
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <ImageAutoUpdatePanel deploymentId="s1" hostId="h1" info={info} canWrite={canWrite} />
    </QueryClientProvider>,
  )
}

describe('ImageAutoUpdatePanel', () => {
  it('explains the behavior and states that times are UTC', () => {
    renderPanel()
    const panel = screen.getByRole('region', { name: 'Automatic image updates' })
    expect(panel).toHaveTextContent(/UTC/)
    expect(panel).toHaveTextContent(/rolled back/i)
    expect(panel).toHaveTextContent(/locked/i)
    expect(screen.getByText('Any time')).toBeInTheDocument()
  })

  it('saves enabled without a window as nulls', async () => {
    apiFetch.mockResolvedValue({})
    renderPanel()
    fireEvent.click(screen.getByRole('switch', { name: 'Automatic image updates' }))
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() =>
      expect(apiFetch).toHaveBeenCalledWith(URL, { method: 'PUT', body: { enabled: true, windowStart: null, windowEnd: null } }),
    )
    expect(await screen.findByRole('status')).toHaveTextContent('Saved')
  })

  it('saves a window', async () => {
    apiFetch.mockResolvedValue({})
    renderPanel({ ...off, enabled: true, windowStart: '02:00', windowEnd: '05:30' })
    expect(screen.getByLabelText('Window start (UTC)')).toHaveValue('02:00')
    fireEvent.change(screen.getByLabelText('Window end (UTC)'), { target: { value: '06:00' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() =>
      expect(apiFetch).toHaveBeenCalledWith(URL, { method: 'PUT', body: { enabled: true, windowStart: '02:00', windowEnd: '06:00' } }),
    )
  })

  it('rejects a half-filled window and an empty one-hour-less window without calling the API', () => {
    renderPanel()
    fireEvent.change(screen.getByLabelText('Window start (UTC)'), { target: { value: '02:00' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    expect(screen.getByRole('alert')).toHaveTextContent(/both/i)
    fireEvent.change(screen.getByLabelText('Window end (UTC)'), { target: { value: '02:00' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    expect(screen.getByRole('alert')).toHaveTextContent(/different/i)
    expect(apiFetch).not.toHaveBeenCalled()
  })

  it('maps API errors inline', async () => {
    apiFetch.mockRejectedValue(new ApiError(400, 'x', 'NOT_A_FILE_DEPLOYMENT'))
    renderPanel()
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    expect(await screen.findByRole('alert')).toHaveTextContent(/Git/)
  })

  it('shows last attempt and result badges', () => {
    renderPanel({ ...off, enabled: true, lastAttemptAt: '2026-10-02T03:00:00Z', lastResult: 'APPLIED' })
    expect(screen.getByText('Applied')).toBeInTheDocument()
    expect(screen.getByText(/Last attempt/)).toBeInTheDocument()
    cleanup()
    renderPanel({ ...off, enabled: true, lastAttemptAt: '2026-10-02T03:00:00Z', lastResult: 'FAILED' })
    expect(screen.getByText(/Failed/)).toBeInTheDocument()
  })

  it('is read-only without write rights', () => {
    renderPanel({ ...off, enabled: true }, false)
    expect(screen.getByRole('switch', { name: 'Automatic image updates' })).toBeDisabled()
    expect(screen.queryByRole('button', { name: 'Save' })).toBeNull()
  })
})
