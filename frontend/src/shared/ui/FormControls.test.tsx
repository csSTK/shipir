import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { Select, Switch, TextField } from './index.ts'

afterEach(cleanup)

describe('TextField', () => {
  it('ties label, help and error to the input', () => {
    render(<TextField label="Name" help="Shown in lists" error="Required" defaultValue="" />)
    const input = screen.getByLabelText('Name')
    expect(input).toHaveAttribute('aria-invalid', 'true')
    expect(input).toHaveAccessibleDescription('Shown in lists Required')
    expect(screen.getByRole('alert')).toHaveTextContent('Required')
  })

  it('reports changes and has no invalid flag without an error', () => {
    const onChange = vi.fn()
    render(<TextField label="Name" value="" onChange={onChange} />)
    const input = screen.getByLabelText('Name')
    expect(input).not.toHaveAttribute('aria-invalid')
    fireEvent.change(input, { target: { value: 'x' } })
    expect(onChange).toHaveBeenCalled()
  })
})

describe('Select', () => {
  it('is labelled and selectable, label can be visually hidden', () => {
    const onChange = vi.fn()
    render(
      <Select label="Role" hideLabel value="a" onChange={onChange}>
        <option value="a">A</option>
        <option value="b">B</option>
      </Select>,
    )
    fireEvent.change(screen.getByRole('combobox', { name: 'Role' }), { target: { value: 'b' } })
    expect(onChange).toHaveBeenCalled()
  })
})

describe('Switch', () => {
  it('toggles as a switch by label', () => {
    const onChange = vi.fn()
    render(<Switch label="Enabled" checked={false} onChange={onChange} />)
    const sw = screen.getByRole('switch', { name: 'Enabled' })
    fireEvent.click(sw)
    expect(onChange).toHaveBeenCalledTimes(1)
  })
})
