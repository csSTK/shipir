import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { YamlDiffEditor } from './YamlDiffEditor.tsx'

afterEach(cleanup)

describe('YamlDiffEditor', () => {
  it('highlights as yaml by default and takes another language', async () => {
    const { rerender } = render(<YamlDiffEditor original="a" modified="b" label="Diff" />)
    expect(await screen.findByLabelText('Diff')).toHaveAttribute('data-language', 'yaml')
    rerender(<YamlDiffEditor original="a" modified="b" label="Diff" language="plaintext" />)
    expect(await screen.findByLabelText('Diff')).toHaveAttribute('data-language', 'plaintext')
  })
})
