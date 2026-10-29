// 7-bit and 8-bit (U+009B) CSI with an optional final byte (so an unterminated one at the end of the line goes too),
// OSC (ESC ] ... BEL or ESC \), other two-byte escapes, C1 controls, C0 controls except tab, and bidi/format chars (ZWNJ/ZWJ are kept: they are part of emoji, Indic and Persian text).
// Every alternative starts with a distinct character and the quantified classes are disjoint, so matching is linear.
// eslint-disable-next-line no-control-regex
const ANSI = /(?:\u001b\[|\u009b)[0-?]*[ -/]*[@-~]?|\u001b\][^\u0007\u001b]*(?:\u0007|\u001b\\)?|\u001b[@-Z\\-_]?|[\u0000-\u0008\u000b-\u001f\u007f-\u009f\u200b\u200e\u200f\u2028-\u202e\u2066-\u2069\ufeff]/g

/**
 * Removes ANSI escape sequences and control characters from a log line. Lines are always rendered as
 * React text (never as HTML), so this is about readability, not injection.
 */
export function stripAnsi(text: string): string {
  return text.replace(ANSI, '')
}
