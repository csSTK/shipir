import i18n from 'i18next'

type DateInput = Date | string | number

/**
 * Dates and numbers always go through `Intl` with the active UI language, never a hard-coded locale and
 * never `toLocaleString()` of the browser default. Formatters are created per call, so a language switch
 * takes effect on the next render.
 */
export function formatDate(value: DateInput, options: Intl.DateTimeFormatOptions = { dateStyle: 'medium' }): string {
  return new Intl.DateTimeFormat(i18n.language, options).format(new Date(value))
}

export function formatDateTime(value: DateInput, options: Intl.DateTimeFormatOptions = { dateStyle: 'medium', timeStyle: 'short' }): string {
  return formatDate(value, options)
}

/** Drop-in for a module-level `Intl.DateTimeFormat`: same `format()`, but resolved with the current language. */
export function dateTimeFormat(options: Intl.DateTimeFormatOptions): { format(value: Date | number): string } {
  return { format: (value) => formatDate(value, options) }
}

export function formatNumber(value: number, options?: Intl.NumberFormatOptions): string {
  return new Intl.NumberFormat(i18n.language, options).format(value)
}

export function formatRelativeTime(value: number, unit: Intl.RelativeTimeFormatUnit): string {
  return new Intl.RelativeTimeFormat(i18n.language, { numeric: 'auto' }).format(value, unit)
}

/** Exactly one decimal place, like `toFixed(1)` but with the locale's decimal separator. */
export const ONE_DECIMAL: Intl.NumberFormatOptions = { minimumFractionDigits: 1, maximumFractionDigits: 1 }
