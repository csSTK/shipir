import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { OneTimeSecret } from './OneTimeSecret.tsx'

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

function stubClipboard(writeText: (text: string) => Promise<void>) {
  Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true })
}

describe('OneTimeSecret', () => {
  it('shows the value read-only and copies it', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    stubClipboard(writeText)
    render(<OneTimeSecret label="Token" value="shp_secret" />)
    expect(screen.getByLabelText('Token')).toHaveValue('shp_secret')
    expect(screen.getByLabelText('Token')).toHaveAttribute('readonly')
    fireEvent.click(screen.getByRole('button', { name: 'Copy' }))
    await waitFor(() => expect(writeText).toHaveBeenCalledWith('shp_secret'))
    expect(await screen.findByRole('button', { name: 'Copied' })).toBeInTheDocument()
  })

  it('tells the user to copy manually when the clipboard is unavailable', async () => {
    stubClipboard(vi.fn().mockRejectedValue(new Error('denied')))
    render(<OneTimeSecret label="Token" value="shp_secret" />)
    fireEvent.click(screen.getByRole('button', { name: 'Copy' }))
    expect(await screen.findByText(/could not copy/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Copy' })).toBeInTheDocument()
  })
})
