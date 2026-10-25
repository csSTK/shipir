import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { TagChips, TagFilter } from './TagChips.tsx'

afterEach(cleanup)

describe('TagChips', () => {
  it('lists each tag as text', () => {
    render(<TagChips tags={['eu', 'prod']} />)
    expect(screen.getByText('eu')).toBeInTheDocument()
    expect(screen.getByText('prod')).toBeInTheDocument()
  })

  it('renders nothing for no tags', () => {
    const { container } = render(<TagChips tags={[]} />)
    expect(container).toBeEmptyDOMElement()
  })
})

describe('TagFilter', () => {
  it('shows a pressed state per selected tag and reports toggles', () => {
    const onToggle = vi.fn()
    render(<TagFilter tags={['eu', 'prod']} selected={['prod']} onToggle={onToggle} />)
    expect(screen.getByRole('button', { name: 'eu' })).toHaveAttribute('aria-pressed', 'false')
    expect(screen.getByRole('button', { name: 'prod' })).toHaveAttribute('aria-pressed', 'true')
    fireEvent.click(screen.getByRole('button', { name: 'eu' }))
    expect(onToggle).toHaveBeenCalledWith('eu')
  })
})
