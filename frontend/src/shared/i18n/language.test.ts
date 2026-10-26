import { afterEach, describe, expect, it } from 'vitest'
import { applyLanguage, browserLanguage, i18n, resolveLanguage } from './index.ts'
import { formatDate, formatNumber } from './format.ts'

afterEach(() => applyLanguage('en'))

describe('browserLanguage', () => {
  it('takes the first supported language of the preference list', () => {
    expect(browserLanguage(['fr-FR', 'de-AT', 'en'])).toBe('de')
  })
  it('falls back to English when nothing supported is preferred', () => {
    expect(browserLanguage(['fr', 'ja'])).toBe('en')
    expect(browserLanguage([])).toBe('en')
  })
})

describe('resolveLanguage', () => {
  it('prefers the chosen locale over the browser language', () => {
    expect(resolveLanguage('en', 'de')).toBe('en')
    expect(resolveLanguage('de', 'en')).toBe('de')
  })
  it('follows the browser language while no locale is chosen or the stored one is unknown', () => {
    expect(resolveLanguage(null, 'de')).toBe('de')
    expect(resolveLanguage(undefined, 'de')).toBe('de')
    expect(resolveLanguage('fr', 'de')).toBe('de')
  })
})

describe('applyLanguage', () => {
  it('switches translations, the html lang and the Intl formatting together', () => {
    expect(i18n.t('common:Dialog.cancel')).toBe('Cancel')
    applyLanguage('de')
    expect(i18n.t('common:Dialog.cancel')).toBe('Abbrechen')
    expect(document.documentElement.lang).toBe('de')
    expect(formatNumber(1234.5)).toBe('1.234,5')
    expect(formatDate('2026-03-05T12:00:00Z', { month: 'long', timeZone: 'UTC' })).toBe('März')
  })
})
