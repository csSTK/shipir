import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { YamlEditor } from './YamlEditor.tsx'

afterEach(cleanup)

describe('YamlEditor', () => {
  it('reports edits and is read-only on request', async () => {
    const onChange = vi.fn()
    const { rerender } = render(<YamlEditor label="Compose file" value="a: 1" onChange={onChange} />)
    const field = await screen.findByLabelText('Compose file')
    fireEvent.change(field, { target: { value: 'b: 2' } })
    expect(onChange).toHaveBeenCalledWith('b: 2')
    rerender(<YamlEditor label="Compose file" value="a: 1" readOnly />)
    expect(await screen.findByLabelText('Compose file')).toHaveAttribute('readonly')
  })

  it('shows a server error with its details', async () => {
    render(<YamlEditor label="Compose file" value="" error={{ message: 'The Compose file is not valid.', details: ['services: must be a mapping'] }} />)
    await screen.findByLabelText('Compose file')
    expect(screen.getByRole('alert')).toHaveTextContent('services: must be a mapping')
  })
})
