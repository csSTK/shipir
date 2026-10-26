import i18n from 'i18next'
import { initReactI18next } from 'react-i18next'

type Bundle = Record<string, string>

/** One JSON file per namespace (= feature) under `locales/<lng>/`, bundled eagerly. */
const modules = import.meta.glob<Bundle>('./locales/en/*.json', { eager: true, import: 'default' })

const en: Record<string, Bundle> = {}
for (const [file, bundle] of Object.entries(modules)) {
  const namespace = file.replace('./locales/en/', '').replace('.json', '')
  en[namespace] = bundle
}

export const DEFAULT_LANGUAGE = 'en'

/**
 * Initialises i18next once. English is the only locale for now; the language is fixed, so tests and the
 * app always render the same text. Interpolated values are not escaped here, React escapes on render.
 */
void i18n.use(initReactI18next).init({
  lng: DEFAULT_LANGUAGE,
  fallbackLng: DEFAULT_LANGUAGE,
  resources: { [DEFAULT_LANGUAGE]: en },
  ns: Object.keys(en),
  defaultNS: 'common',
  interpolation: { escapeValue: false },
  returnNull: false,
})

export { i18n }
export { dateTimeFormat, formatDate, formatDateTime, formatNumber, formatRelativeTime } from './format.ts'
