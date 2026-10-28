import { describe, expect, it } from 'vitest'

import { formalAddressSentences } from './formalAddress'

type Bundle = Record<string, string>

const toBundles = (modules: Record<string, Bundle>): Record<string, Bundle> =>
  Object.fromEntries(Object.entries(modules).map(([file, bundle]) => [/([^/]+)\.json$/.exec(file)![1], bundle]))

const en = toBundles(import.meta.glob<Bundle>('./locales/en/*.json', { eager: true, import: 'default' }))
const de = toBundles(import.meta.glob<Bundle>('./locales/de/*.json', { eager: true, import: 'default' }))

/**
 * Sentences in de/*.json that start with a capitalised 'Sie' but mean the plural 'they' (tokens, recovery codes),
 * not formal address. Keep each entry exact: a changed sentence fails the test again and must be re-checked.
 */
const ALLOWED_PLURAL_SIE: { key: string; sentence: string }[] = [
  {
    key: 'account:TokensSection.tokensLetScriptsCallThe',
    sentence: 'Sie funktionieren im Authorization-Header, nicht für die Anmeldung oder die Verwaltung von Tokens.',
  },
  {
    key: 'auth:TwoFactorEnrollment.twoFactorAuthenticationIsOn',
    sentence: 'Sie werden nur jetzt angezeigt.',
  },
]

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

  it('addresses the user informally (du), never with the formal Sie/Ihr', () => {
    const formal = Object.entries(de).flatMap(([ns, bundle]) =>
      Object.entries(bundle).flatMap(([key, text]) =>
        formalAddressSentences(text)
          .filter((sentence) => !ALLOWED_PLURAL_SIE.some((allowed) => allowed.key === `${ns}:${key}` && allowed.sentence === sentence))
          .map((sentence) => `${ns}:${key}: ${sentence}`),
      ),
    )
    expect(formal).toEqual([])
  })
})

describe('formal address check', () => {
  it.each([
    'Sie können Container starten.',
    'Fertig. Sie können weitermachen.',
    'Bitte bestätigen Sie die Eingabe.',
    'Ihre Sitzung ist abgelaufen.',
    'Wenn Ihnen das nicht passt, melde dich.',
    'Fertig. Ihr Konto ist gesperrt.',
    '{{name}} Sie können das nicht ändern.',
    'Achtung: <strong>Sie</strong> können das nicht ändern.',
    'Hinweis – Sie können das nicht ändern.',
    'Option (Sie können das nicht ändern).',
    'Option [Sie] wählen.',
    "Bitte 'Sie' wählen.",
    'Pfad/Sie/können',
    ' Sie können das nicht ändern.',
    'Speichern Sie.',
  ])('flags formal address in "%s"', (text) => {
    expect(formalAddressSentences(text)).toHaveLength(1)
  })

  it.each([
    'Speichere sie sicher.',
    'Tokens können Skripte aufrufen; sie funktionieren im Header.',
    'Du kannst deine Tokens bearbeiten.',
    'Die Container laufen.',
    'Siebzehn Container laufen.',
    'Ihrerseits ist alles gut.',
  ])('allows informal or non-pronoun text "%s"', (text) => {
    expect(formalAddressSentences(text)).toEqual([])
  })
})
