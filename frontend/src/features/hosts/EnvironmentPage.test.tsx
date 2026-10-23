import { cleanup, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, expect, it } from 'vitest'
import { EnvironmentPage } from './EnvironmentPage.tsx'

afterEach(cleanup)

it('is a placeholder with a way back', () => {
  render(
    <MemoryRouter>
      <EnvironmentPage />
    </MemoryRouter>,
  )
  expect(screen.getByText('Coming soon')).toBeInTheDocument()
  expect(screen.getByRole('link', { name: /environments/i })).toHaveAttribute('href', '/')
})
