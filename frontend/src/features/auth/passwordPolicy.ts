import i18n from 'i18next'
/**
 * Client-side mirror of the backend password policy, used only for live feedback.
 * The server stays authoritative (400 PASSWORD_POLICY).
 *
 * Length is `String.length` (UTF-16 code units), the same unit Java's `String.length()` counts.
 * A symbol is any character that is neither a Unicode letter nor a decimal digit (spaces count).
 * The username comparison ignores case; this is stricter than necessary, which only errs towards
 * a clearer hint and is corrected by the server's verdict.
 */
export const MIN_LENGTH = 15
export const MAX_LENGTH = 128

export type RuleId =
  | 'length'
  | 'uppercase'
  | 'lowercase'
  | 'digit'
  | 'symbol'
  | 'notUsername'
  | 'match'

export interface RuleResult {
  id: RuleId
  label: string
  met: boolean
}

export function checkPassword(password: string, username: string, confirmation: string): RuleResult[] {
  return [
    {
      id: 'length',
      label: i18n.t('auth:passwordPolicy.minToMaxCharacters', { min: MIN_LENGTH, max: MAX_LENGTH }),
      met: password.length >= MIN_LENGTH && password.length <= MAX_LENGTH,
    },
    { id: 'uppercase', label: i18n.t('auth:passwordPolicy.anUppercaseLetter'), met: /\p{Lu}/u.test(password) },
    { id: 'lowercase', label: i18n.t('auth:passwordPolicy.aLowercaseLetter'), met: /\p{Ll}/u.test(password) },
    { id: 'digit', label: i18n.t('auth:passwordPolicy.aDigit'), met: /\p{Nd}/u.test(password) },
    { id: 'symbol', label: i18n.t('auth:passwordPolicy.aSymbolNotALetter'), met: /[^\p{L}\p{Nd}]/u.test(password) },
    {
      id: 'notUsername',
      label: i18n.t('auth:passwordPolicy.differentFromYourUsername'),
      met: password.length > 0 && password.toLowerCase() !== username.toLowerCase(),
    },
    { id: 'match', label: i18n.t('auth:passwordPolicy.matchesTheConfirmation'), met: password.length > 0 && password === confirmation },
  ]
}

export function isPasswordValid(password: string, username: string, confirmation: string): boolean {
  return checkPassword(password, username, confirmation).every((rule) => rule.met)
}

const VIOLATION_MESSAGES: Record<string, string> = {
  get TOO_SHORT() { return i18n.t('auth:passwordPolicy.thePasswordMustBeAt', { MIN_LENGTH }) },
  get TOO_LONG() { return i18n.t('auth:passwordPolicy.thePasswordMustBeAt2', { MAX_LENGTH }) },
  get MISSING_UPPERCASE() { return i18n.t('auth:passwordPolicy.thePasswordNeedsAnUppercase') },
  get MISSING_LOWERCASE() { return i18n.t('auth:passwordPolicy.thePasswordNeedsALowercase') },
  get MISSING_DIGIT() { return i18n.t('auth:passwordPolicy.thePasswordNeedsADigit') },
  get MISSING_SYMBOL() { return i18n.t('auth:passwordPolicy.thePasswordNeedsASymbol') },
  get EQUALS_USERNAME() { return i18n.t('auth:passwordPolicy.thePasswordMustDifferFrom') },
  get SAME_AS_CURRENT() { return i18n.t('auth:passwordPolicy.theNewPasswordMustBe') },
}

/** Readable text for a backend `PASSWORD_POLICY` violation code. */
export function violationMessage(code: string): string {
  return VIOLATION_MESSAGES[code] ?? i18n.t('auth:passwordPolicy.thePasswordDoesNotMeet')
}
