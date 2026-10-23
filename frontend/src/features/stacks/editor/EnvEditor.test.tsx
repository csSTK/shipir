import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { useState } from 'react'
import { afterEach, describe, expect, it } from 'vitest'
import type { EnvEntry } from '../api.ts'
import { EnvEditor } from './EnvEditor.tsx'

afterEach(cleanup)

let latest: EnvEntry[] = []
function Harness({ initial = [] }: { initial?: EnvEntry[] }) {
  const [rows, setRows] = useState(initial)
  return (
    <EnvEditor
      value={rows}
      onChange={(next) => {
        latest = next
        setRows(next)
      }}
    />
  )
}

describe('EnvEditor', () => {
  it('adds and removes rows, values are masked until revealed', () => {
    render(<Harness />)
    fireEvent.click(screen.getByRole('button', { name: 'Add variable' }))
    fireEvent.change(screen.getByLabelText('Variable 1 name'), { target: { value: 'TOKEN' } })
    fireEvent.change(screen.getByLabelText('Variable 1 value'), { target: { value: 'abc' } })
    expect(screen.getByLabelText('Variable 1 value')).toHaveAttribute('type', 'password')
    fireEvent.click(screen.getByRole('button', { name: 'Show values' }))
    expect(screen.getByLabelText('Variable 1 value')).toHaveAttribute('type', 'text')
    expect(latest).toEqual([{ key: 'TOKEN', value: 'abc' }])
    fireEvent.click(screen.getByRole('button', { name: 'Remove variable 1' }))
    expect(latest).toEqual([])
  })

  it('flags invalid and duplicate names', () => {
    render(
      <Harness
        initial={[
          { key: 'A', value: '' },
          { key: 'A', value: '' },
          { key: '9x', value: '' },
        ]}
      />,
    )
    const alerts = screen.getAllByRole('alert')
    expect(alerts[0]).toHaveTextContent('more than once')
    expect(alerts[1]).toHaveTextContent('do not start with a digit')
  })

  it('merges pasted .env text and reports skipped lines', () => {
    render(<Harness initial={[{ key: 'A', value: 'old' }]} />)
    fireEvent.click(screen.getByRole('button', { name: 'Paste .env' }))
    fireEvent.change(screen.getByLabelText('Paste the contents of a .env file'), { target: { value: '# c\nA=new\nB="x y"\nbroken' } })
    fireEvent.click(screen.getByRole('button', { name: 'Add variables' }))
    expect(latest).toEqual([
      { key: 'A', value: 'new' },
      { key: 'B', value: 'x y' },
    ])
    expect(screen.getByRole('status')).toHaveTextContent('Skipped line 4')
  })
})
