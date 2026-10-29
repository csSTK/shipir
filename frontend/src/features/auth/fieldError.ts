/**
 * ARIA wiring for an input whose error is rendered in a separate element (never inside the label,
 * so the accessible name stays clean). Spread onto the input; empty while the field is valid.
 */
export function fieldError(errorId: string, invalid: boolean): { 'aria-describedby'?: string; 'aria-invalid'?: true } {
  return invalid ? { 'aria-describedby': errorId, 'aria-invalid': true } : {}
}
