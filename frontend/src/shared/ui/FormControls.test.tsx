import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { Checkbox, Select, Switch, TextField } from './index.ts'

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

describe('TextField hideLabel', () => {
  it('keeps the label in the DOM as the accessible name, visually hidden, without a wrapper class', () => {
    render(<TextField label="Port 1" hideLabel defaultValue="" />)
    const input = screen.getByRole('textbox', { name: 'Port 1' })
    expect(input.closest('.ui-field--bare')).toBeNull()
    expect(screen.getByText('Port 1', { selector: 'label' })).toHaveClass('visually-hidden')
  })

  it('shows the label without hideLabel', () => {
    render(<TextField label="Port 1" defaultValue="" />)
    expect(screen.getByText('Port 1', { selector: 'label' })).not.toHaveClass('visually-hidden')
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
    const select = screen.getByRole('combobox', { name: 'Role' })
    expect(select.closest('.ui-field--bare')).toBeNull()
    expect(screen.getByText('Role', { selector: 'label' })).toHaveClass('visually-hidden')
    fireEvent.change(select, { target: { value: 'b' } })
    expect(onChange).toHaveBeenCalled()
  })

  it('shows the label without hideLabel', () => {
    render(
      <Select label="Role" defaultValue="a">
        <option value="a">A</option>
      </Select>,
    )
    expect(screen.getByText('Role', { selector: 'label' })).not.toHaveClass('visually-hidden')
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

  it('keeps the help link when the caller passes its own aria-describedby', () => {
    render(<Switch label="Enabled" help="Explains it" aria-describedby="extra" />)
    const description = screen.getByRole('switch', { name: 'Enabled' }).getAttribute('aria-describedby') ?? ''
    expect(description).toContain('extra')
    expect(screen.getByRole('switch')).toHaveAccessibleDescription(/Explains it/)
  })
})

describe('Checkbox', () => {
  it('is a checkbox named by its label and reports changes', () => {
    const onChange = vi.fn()
    render(<Checkbox label="Remove volumes" checked={false} onChange={onChange} />)
    fireEvent.click(screen.getByRole('checkbox', { name: 'Remove volumes' }))
    expect(onChange).toHaveBeenCalledTimes(1)
  })

  it('reflects the checked state and can be disabled', () => {
    render(<Checkbox label="Force" checked readOnly disabled />)
    const box = screen.getByLabelText('Force')
    expect(box).toBeChecked()
    expect(box).toBeDisabled()
  })

  it('forwards className to the row and extra props to the input', () => {
    const { container } = render(<Checkbox label="Profile" className="chip" name="profile" defaultChecked />)
    expect(container.querySelector('label.chip')).not.toBeNull()
    expect(screen.getByRole('checkbox')).toHaveAttribute('name', 'profile')
  })
})
