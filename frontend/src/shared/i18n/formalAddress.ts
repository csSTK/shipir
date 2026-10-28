/**
 * Formal German address (Sie/Ihr and their inflections), capitalised, as a whole word: not preceded or followed by a
 * letter or digit, so it is found after spaces, punctuation, quotes, brackets, dashes, `}}` and `>`. Lowercase 'sie' is
 * the pronoun (also plural 'they' in tokens and codes) and is never matched. A capitalised sentence-initial 'Sie' can
 * still mean 'they'; those sentences need an allowlist entry in the test, not a looser pattern.
 */
const FORMAL_ADDRESS = /(?<![\p{L}\p{N}])(Sie|Ihr|Ihre|Ihrem|Ihren|Ihrer|Ihres|Ihnen)(?![\p{L}\p{N}])/u

/** The sentences of `text` that contain formal address; empty when the text is informal. */
export const formalAddressSentences = (text: string): string[] =>
  text.split(/(?<=[.!?])\s+/).filter((sentence) => FORMAL_ADDRESS.test(sentence))
