import { describe, expect, it } from 'vitest'
import { stripAnsi } from './ansi.ts'

describe('stripAnsi', () => {
  it('removes colour (SGR) sequences', () => {
    expect(stripAnsi('\u001b[31mred\u001b[0m text \u001b[1;32mgreen\u001b[m')).toBe('red text green')
  })

  it('removes cursor and erase sequences', () => {
    expect(stripAnsi('a\u001b[2K\u001b[1Ab\u001b[10;20Hc')).toBe('abc')
  })

  it('removes OSC sequences (BEL and ST terminated)', () => {
    expect(stripAnsi('\u001b]0;title\u0007x\u001b]8;;http://e\u001b\\y')).toBe('xy')
  })

  it('removes a lone escape and other C0 control characters but keeps tab', () => {
    expect(stripAnsi('a\u001bb\u0000c\u0008d\te')).toBe('abcd\te')
  })

  it('leaves HTML untouched (rendering as text is the defence)', () => {
    expect(stripAnsi('\u001b[31m<img src=x onerror=alert(1)>\u001b[0m')).toBe('<img src=x onerror=alert(1)>')
  })

  it('returns plain text unchanged', () => {
    expect(stripAnsi('plain [31m not an escape')).toBe('plain [31m not an escape')
  })

  it('removes 8-bit CSI (U+009B) with its parameters and other C1 controls', () => {
    expect(stripAnsi('a\u009b31mb\u0085c\u009dd')).toBe('abcd')
  })

  it('removes bidi and zero-width format characters', () => {
    expect(stripAnsi('a\u202eb\u2066c\u200bd\u2028e\ufeff')).toBe('abcde')
  })

  it('removes an unterminated CSI at the end of the line', () => {
    expect(stripAnsi('x\u001b[31')).toBe('x')
    expect(stripAnsi('x\u009b31')).toBe('x')
  })

  it('stays linear on long inputs without a terminator', () => {
    const start = performance.now()
    expect(stripAnsi('\u001b]' + 'a'.repeat(1e5))).toBe('')
    expect(stripAnsi('\u001b[' + '1'.repeat(1e5))).toBe('')
    expect(stripAnsi('\u001b['.repeat(5e4))).toBe('')
    expect(performance.now() - start).toBeLessThan(1000)
  })

  it('keeps ZWJ and ZWNJ, which carry meaning in emoji, Indic and Persian text', () => {
    const family = '\u{1F468}\u200d\u{1F469}\u200d\u{1F467}'
    const hindi = '\u0915\u094d\u200d\u0937'
    const persian = '\u0645\u06cc\u200c\u062e\u0648\u0627\u0647\u0645'
    expect(stripAnsi(family)).toBe(family)
    expect(stripAnsi(hindi)).toBe(hindi)
    expect(stripAnsi(persian)).toBe(persian)
  })
})
