import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { createRef } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { TagInput, type TagInputHandle } from './TagInput.tsx'

afterEach(cleanup)

function setup(tags: string[] = []) {
  const onChange = vi.fn()
  const ref = createRef<TagInputHandle>()
  render(<TagInput tags={tags} onChange={onChange} label="Tags" ref={ref} />)
  return { onChange, ref, input: screen.getByRole('textbox', { name: 'Tags' }) }
}

describe('TagInput', () => {
  it('adds a normalised tag on Enter and keeps the form from submitting', () => {
    const { input, onChange } = setup()
    fireEvent.change(input, { target: { value: ' Prod ' } })
    const notPrevented = fireEvent.keyDown(input, { key: 'Enter' })
    expect(notPrevented).toBe(false)
    expect(onChange).toHaveBeenCalledWith(['prod'])
  })

  it('adds a tag on comma', () => {
    const { input, onChange } = setup(['eu'])
    fireEvent.change(input, { target: { value: 'prod,' } })
    expect(onChange).toHaveBeenCalledWith(['eu', 'prod'])
  })

  it('removes the last tag on Backspace in an empty input', () => {
    const { input, onChange } = setup(['eu', 'prod'])
    fireEvent.keyDown(input, { key: 'Backspace' })
    expect(onChange).toHaveBeenCalledWith(['eu'])
  })

  it('removes a tag with its remove button', () => {
    const { onChange } = setup(['eu', 'prod'])
    fireEvent.click(screen.getByRole('button', { name: 'Remove tag prod' }))
    expect(onChange).toHaveBeenCalledWith(['eu'])
  })

  it('shows an inline alert for an invalid tag and keeps the draft', () => {
    const { input, onChange } = setup()
    fireEvent.change(input, { target: { value: 'Bad Tag' } })
    fireEvent.keyDown(input, { key: 'Enter' })
    expect(screen.getByRole('alert')).toHaveTextContent(/lowercase letters, digits or hyphens/i)
    expect(onChange).not.toHaveBeenCalled()
    expect(input).toHaveValue('Bad Tag')
  })

  it('commit adds the pending draft and returns the final list', () => {
    const { input, ref } = setup(['eu'])
    fireEvent.change(input, { target: { value: 'prod' } })
    let result: string[] | null = null
    act(() => {
      result = ref.current?.commit() ?? null
    })
    expect(result).toEqual(['eu', 'prod'])
  })

  it('commit returns null for an invalid draft', () => {
    const { input, ref } = setup()
    fireEvent.change(input, { target: { value: '-x' } })
    act(() => {
      expect(ref.current?.commit()).toBeNull()
    })
    expect(screen.getByRole('alert')).toBeInTheDocument()
  })

  it('ignores Enter during IME composition: no commit and no alert', () => {
    const { input, onChange } = setup()
    fireEvent.change(input, { target: { value: 'prod' } })
    fireEvent.keyDown(input, { key: 'Enter', isComposing: true })
    expect(onChange).not.toHaveBeenCalled()
    expect(screen.queryByRole('alert')).toBeNull()
    expect(input).toHaveValue('prod')
  })

  it('moves focus to the text input after a chip is removed', () => {
    const { onChange } = setup(['eu', 'prod'])
    fireEvent.click(screen.getByRole('button', { name: 'Remove tag prod' }))
    expect(onChange).toHaveBeenCalledWith(['eu'])
    expect(screen.getByRole('textbox', { name: 'Tags' })).toHaveFocus()
  })
})
