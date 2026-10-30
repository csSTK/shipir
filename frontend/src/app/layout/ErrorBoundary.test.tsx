import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, Link } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ErrorBoundary } from './ErrorBoundary.tsx'

afterEach(cleanup)

let broken = true
function Page() {
  if (broken) throw new Error('boom')
  return <p>page ok</p>
}

function setup() {
  vi.spyOn(console, 'error').mockImplementation(() => {})
  return render(
    <MemoryRouter>
      <Link to="/other">other</Link>
      <ErrorBoundary resetKey="/">
        <Page />
      </ErrorBoundary>
    </MemoryRouter>,
  )
}

describe('ErrorBoundary', () => {
  it('shows a friendly error state instead of a blank app', () => {
    broken = true
    setup()
    expect(screen.getByRole('alert')).toBeTruthy()
    expect(screen.getByRole('button', { name: /try again/i })).toBeTruthy()
    expect(screen.getByRole('link', { name: /back to start/i }).getAttribute('href')).toBe('/')
    expect(screen.queryByText('page ok')).toBeNull()
  })

  it('retry remounts the page', () => {
    broken = true
    setup()
    broken = false
    fireEvent.click(screen.getByRole('button', { name: /try again/i }))
    expect(screen.getByText('page ok')).toBeTruthy()
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('resets when the reset key changes (route change)', () => {
    broken = true
    const ui = (key: string) => (
      <MemoryRouter>
        <ErrorBoundary resetKey={key}>
          <Page />
        </ErrorBoundary>
      </MemoryRouter>
    )
    vi.spyOn(console, 'error').mockImplementation(() => {})
    const { rerender } = render(ui('/a'))
    expect(screen.getByRole('alert')).toBeTruthy()
    broken = false
    rerender(ui('/b'))
    expect(screen.getByText('page ok')).toBeTruthy()
  })
})
