import { render } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'

// A minimal stand-in for the Monaco instance: the model is a string, like the real one for our purposes.
const fake = vi.hoisted(() => ({ text: '', onChange: undefined as undefined | ((v: string) => void) }))
vi.mock('./monacoEnvironment.ts', () => ({ monacoForYaml: {}, monaco: {} }))
vi.mock('monaco-yaml', () => ({ configureMonacoYaml: vi.fn() }))
vi.mock('@monaco-editor/react', async () => {
  const { useEffect } = await import('react')
  return {
    default: function FakeEditor(props: { defaultValue?: string; value?: string; onMount?: (e: unknown) => void; onChange?: (v: string) => void }) {
      useEffect(() => {
        fake.text = props.value ?? props.defaultValue ?? ''
        props.onMount?.({
          getValue: () => fake.text,
          setValue: (v: string) => { fake.text = v },
          getModel: () => ({ getFullModelRange: () => ({}) }),
          executeEdits: (_s: string, edits: { text: string }[]) => { fake.text = edits[0].text; props.onChange?.(fake.text) },
          pushUndoStop: () => {},
        })
        // eslint-disable-next-line react-hooks/exhaustive-deps
      }, [])
      // Like the real library: a controlled `value` that differs from the model overwrites the whole model.
      useEffect(() => {
        if (props.value !== undefined && props.value !== fake.text) fake.text = props.value
      }, [props.value])
      fake.onChange = props.onChange
      return null
    },
  }
})

// The test setup replaces MonacoYamlEditor with a textarea; this file tests the real component.
const { default: MonacoYamlEditor } = await vi.importActual<typeof import('./MonacoYamlEditor.tsx')>('./MonacoYamlEditor.tsx')

function type(text: string) {
  fake.text = text
  fake.onChange?.(text)
}

describe('MonacoYamlEditor', () => {
  it('applies a reset to the starting value after the user edited', () => {
    const onChange = vi.fn()
    const { rerender } = render(<MonacoYamlEditor value="" onChange={onChange} label="l" height="100px" />)
    type('abc')
    rerender(<MonacoYamlEditor value="abc" onChange={onChange} label="l" height="100px" />)
    expect(fake.text).toBe('abc')
    rerender(<MonacoYamlEditor value="" onChange={onChange} label="l" height="100px" />)
    expect(fake.text).toBe('')
    expect(onChange).toHaveBeenCalledTimes(1) // the programmatic reset is not reported back as a user edit
  })

  it('ignores a stale echo of its own onChange while the user keeps typing', () => {
    const onChange = vi.fn()
    const { rerender } = render(<MonacoYamlEditor value="" onChange={onChange} label="l" height="100px" />)
    type('h')
    type('ht') // typed before the parent re-rendered with 'h'
    rerender(<MonacoYamlEditor value="h" onChange={onChange} label="l" height="100px" />)
    expect(fake.text).toBe('ht')
    rerender(<MonacoYamlEditor value="ht" onChange={onChange} label="l" height="100px" />)
    expect(fake.text).toBe('ht')
  })
})
