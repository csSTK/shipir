import i18n from 'i18next'

export const SUPPORTED_LANGUAGES = ['en', 'de'] as const
export type Language = (typeof SUPPORTED_LANGUAGES)[number]
export const DEFAULT_LANGUAGE: Language = 'en'

export function isSupportedLanguage(value: unknown): value is Language {
  return typeof value === 'string' && (SUPPORTED_LANGUAGES as readonly string[]).includes(value)
}

/** First supported language of the browser's preference list (`de-AT` counts as `de`), else English. */
export function browserLanguage(preferred: readonly string[] = typeof navigator === 'undefined' ? [] : navigator.languages): Language {
  for (const tag of preferred) {
    const primary = tag.toLowerCase().split('-')[0]
    if (isSupportedLanguage(primary)) return primary
  }
  return DEFAULT_LANGUAGE
}

/** The user's chosen locale, or the browser language while they have not chosen one. */
export function resolveLanguage(locale: string | null | undefined, browser: Language = browserLanguage()): Language {
  return isSupportedLanguage(locale) ? locale : browser
}

/** Switches the UI language (i18next, `<html lang>`). A no-op for the current language. */
export function applyLanguage(language: Language): void {
  if (i18n.language === language) return
  void i18n.changeLanguage(language)
  document.documentElement.lang = language
}
