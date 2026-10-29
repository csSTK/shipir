import { dateTimeFormat } from '../../shared/i18n/format.ts'

/** Date and time as shown across the volume backup UI (medium date, short time, current language). */
export const dateFormat = dateTimeFormat({ dateStyle: 'medium', timeStyle: 'short' })
