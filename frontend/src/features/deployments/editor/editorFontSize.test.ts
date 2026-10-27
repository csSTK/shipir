import { afterEach, describe, expect, it } from 'vitest'
import { editorFontSize } from './editorFontSize.ts'

describe('editorFontSize', () => {
  afterEach(() => document.documentElement.removeAttribute('style'))

  it('resolves the small text token against the root font size', () => {
    document.documentElement.style.setProperty('--text-small-size', '0.8125rem')
    document.documentElement.style.fontSize = '16px'
    expect(editorFontSize()).toBe(13)
  })

  it('falls back to 13 when the token is missing', () => {
    expect(editorFontSize()).toBe(13)
  })
})
