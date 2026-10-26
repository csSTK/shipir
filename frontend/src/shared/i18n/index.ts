import i18n from 'i18next'
import { initReactI18next } from 'react-i18next'
import { DEFAULT_LANGUAGE } from './language.ts'

type Bundle = Record<string, string>

/** One JSON file per namespace (= feature) under `locales/<lng>/`, all languages bundled eagerly. */
const modules = import.meta.glob<Bundle>('./locales/*/*.json', { eager: true, import: 'default' })

const resources: Record<string, Record<string, Bundle>> = {}
for (const [file, bundle] of Object.entries(modules)) {
  const [, language, name] = /^\.\/locales\/([^/]+)\/(.+)\.json$/.exec(file)!
  ;(resources[language] ??= {})[name] = bundle
}

/**
 * Initialises i18next once, always in English (so tests render the same text); the app switches to the
 * user's language after sign-in via `applyLanguage`. Missing German keys fall back to English. Interpolated
 * values are not escaped here, React escapes on render.
 */
void i18n.use(initReactI18next).init({
  lng: DEFAULT_LANGUAGE,
  fallbackLng: DEFAULT_LANGUAGE,
  resources,
  ns: Object.keys(resources[DEFAULT_LANGUAGE]),
  defaultNS: 'common',
  interpolation: { escapeValue: false },
  returnNull: false,
})

export { i18n }
export { DEFAULT_LANGUAGE, SUPPORTED_LANGUAGES, applyLanguage, browserLanguage, isSupportedLanguage, resolveLanguage, type Language } from './language.ts'
export { dateTimeFormat, formatDate, formatDateTime, formatNumber, formatRelativeTime } from './format.ts'
