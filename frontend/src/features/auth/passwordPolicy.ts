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
      label: `${MIN_LENGTH} to ${MAX_LENGTH} characters`,
      met: password.length >= MIN_LENGTH && password.length <= MAX_LENGTH,
    },
    { id: 'uppercase', label: 'An uppercase letter', met: /\p{Lu}/u.test(password) },
    { id: 'lowercase', label: 'A lowercase letter', met: /\p{Ll}/u.test(password) },
    { id: 'digit', label: 'A digit', met: /\p{Nd}/u.test(password) },
    { id: 'symbol', label: 'A symbol (not a letter or digit)', met: /[^\p{L}\p{Nd}]/u.test(password) },
    {
      id: 'notUsername',
      label: 'Different from your username',
      met: password.length > 0 && password.toLowerCase() !== username.toLowerCase(),
    },
    { id: 'match', label: 'Matches the confirmation', met: password.length > 0 && password === confirmation },
  ]
}

export function isPasswordValid(password: string, username: string, confirmation: string): boolean {
  return checkPassword(password, username, confirmation).every((rule) => rule.met)
}

const VIOLATION_MESSAGES: Record<string, string> = {
  TOO_SHORT: `The password must be at least ${MIN_LENGTH} characters long.`,
  TOO_LONG: `The password must be at most ${MAX_LENGTH} characters long.`,
  MISSING_UPPERCASE: 'The password needs an uppercase letter.',
  MISSING_LOWERCASE: 'The password needs a lowercase letter.',
  MISSING_DIGIT: 'The password needs a digit.',
  MISSING_SYMBOL: 'The password needs a symbol.',
  EQUALS_USERNAME: 'The password must differ from your username.',
  SAME_AS_CURRENT: 'The new password must be different from the current one.',
}

/** Readable text for a backend `PASSWORD_POLICY` violation code. */
export function violationMessage(code: string): string {
  return VIOLATION_MESSAGES[code] ?? 'The password does not meet the requirements.'
}
