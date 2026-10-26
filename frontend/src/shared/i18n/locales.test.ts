import { describe, expect, it } from 'vitest'

type Bundle = Record<string, string>

const toBundles = (modules: Record<string, Bundle>): Record<string, Bundle> =>
  Object.fromEntries(Object.entries(modules).map(([file, bundle]) => [/([^/]+)\.json$/.exec(file)![1], bundle]))

const en = toBundles(import.meta.glob<Bundle>('./locales/en/*.json', { eager: true, import: 'default' }))
const de = toBundles(import.meta.glob<Bundle>('./locales/de/*.json', { eager: true, import: 'default' }))

/** `{{name}}` placeholders and `<tag>` markers; both must survive translation. */
const placeholders = (text: string) => [...text.matchAll(/\{\{\s*([^}\s,]+)[^}]*\}\}/g)].map((m) => m[1]).sort()
const tags = (text: string) => [...text.matchAll(/<\/?([a-zA-Z0-9]+)\s*\/?>/g)].map((m) => m[1]).sort()

describe('German locale', () => {
  it('has a file for every English namespace and no others', () => {
    expect(Object.keys(de).sort()).toEqual(Object.keys(en).sort())
  })

  it.each(Object.keys(en))('has exactly the English keys in %s', (namespace) => {
    expect(Object.keys(de[namespace] ?? {}).sort()).toEqual(Object.keys(en[namespace]).sort())
  })

  it.each(Object.keys(en))('keeps placeholders and markup of every text in %s', (namespace) => {
    const mismatches = Object.entries(en[namespace])
      .filter(([key, text]) => {
        const german = de[namespace]?.[key] ?? ''
        // The German singular may drop {{count}} ("ein Tag"); every other text keeps exactly the English placeholders.
        const samePlaceholders = key.endsWith('_one')
          ? placeholders(german).every((p) => placeholders(text).includes(p))
          : JSON.stringify(placeholders(german)) === JSON.stringify(placeholders(text))
        return !samePlaceholders || JSON.stringify(tags(german)) !== JSON.stringify(tags(text))
      })
      .map(([key]) => key)
    expect(mismatches).toEqual([])
  })

  it('has no empty texts', () => {
    const empty = Object.entries(de).flatMap(([ns, bundle]) => Object.entries(bundle).filter(([, text]) => text.trim() === '').map(([key]) => `${ns}:${key}`))
    expect(empty).toEqual([])
  })

  it('addresses the user informally (du), never with the formal Sie/Ihr in mid-sentence', () => {
    const formal = Object.entries(de).flatMap(([ns, bundle]) =>
      Object.entries(bundle)
        .filter(([, text]) => /(?<=[a-zäöüß,;] )(Sie|Ihr|Ihre|Ihrem|Ihren|Ihrer|Ihres|Ihnen)\b/.test(text))
        .map(([key]) => `${ns}:${key}`),
    )
    expect(formal).toEqual([])
  })
})
