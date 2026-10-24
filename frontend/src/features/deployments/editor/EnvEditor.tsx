import { useId, useState } from 'react'
import type { EnvEntry } from '../api.ts'
import { mergeEnv, parseEnv, validateEnv, type EnvIssue } from './envFile.ts'
import './editor.css'
import { Button } from '../../../shared/ui/index.ts'

const ISSUE_TEXT: Record<EnvIssue, string> = {
  INVALID_KEY: 'Use letters, digits and underscores; do not start with a digit.',
  DUPLICATE_KEY: 'This name is used more than once.',
}

/**
 * Key/value editor for deployment variables. Values are masked until revealed; a `.env` text can be pasted
 * and is merged into the rows. Problems are shown per row, saving is up to the caller (see `validateEnv`).
 */
export function EnvEditor({ value, onChange }: { value: EnvEntry[]; onChange: (next: EnvEntry[]) => void }) {
  const pasteId = useId()
  const [revealed, setRevealed] = useState(false)
  const [pasting, setPasting] = useState(false)
  const [pasteText, setPasteText] = useState('')
  const [pasteNote, setPasteNote] = useState<string | null>(null)
  const issues = validateEnv(value)

  function setRow(i: number, patch: Partial<EnvEntry>) {
    onChange(value.map((r, j) => (j === i ? { ...r, ...patch } : r)))
  }

  function applyPaste() {
    const { entries, invalidLines } = parseEnv(pasteText)
    if (entries.length === 0) {
      setPasteNote(invalidLines.length > 0 ? 'No KEY=value lines found.' : 'Nothing to add.')
      return
    }
    onChange(mergeEnv(value, entries))
    setPasteText('')
    setPasting(false)
    setPasteNote(
      invalidLines.length > 0
        ? `Added ${entries.length}. Skipped line${invalidLines.length === 1 ? '' : 's'} ${invalidLines.join(', ')}.`
        : `Added ${entries.length} variable${entries.length === 1 ? '' : 's'}.`,
    )
  }

  return (
    <fieldset className="ed-env">
      <legend>Environment variables</legend>
      {value.length === 0 && <p className="ed-hint">No variables.</p>}
      {value.map((row, i) => {
        const issue = issues.get(i)
        return (
          <div className="ed-env__item" key={i}>
            <div className="ed-env__row">
              <input
                aria-label={`Variable ${i + 1} name`}
                aria-invalid={issue ? true : undefined}
                placeholder="NAME"
                value={row.key}
                onChange={(e) => setRow(i, { key: e.target.value })}
                autoComplete="off"
                spellCheck={false}
              />
              <input
                aria-label={`Variable ${i + 1} value`}
                placeholder="value"
                type={revealed ? 'text' : 'password'}
                value={row.value}
                onChange={(e) => setRow(i, { value: e.target.value })}
                autoComplete="off"
                spellCheck={false}
              />
              <Button variant="ghost" aria-label={`Remove variable ${i + 1}`} onClick={() => onChange(value.filter((_, j) => j !== i))}>
                Remove
              </Button>
            </div>
            {issue && (
              <p role="alert" className="ed-env__issue">
                {ISSUE_TEXT[issue]}
              </p>
            )}
          </div>
        )
      })}
      <div className="ed-env__tools">
        <Button variant="ghost" onClick={() => onChange([...value, { key: '', value: '' }])}>
          Add variable
        </Button>
        <Button variant="ghost" aria-expanded={pasting} onClick={() => setPasting((p) => !p)}>
          Paste .env
        </Button>
        <Button variant="ghost" aria-pressed={revealed} onClick={() => setRevealed((r) => !r)}>
          {revealed ? 'Hide values' : 'Show values'}
        </Button>
      </div>
      {pasting && (
        <div className="ed-env__paste">
          <label htmlFor={pasteId} className="ed-hint">
            Paste the contents of a .env file
          </label>
          <textarea id={pasteId} className="ui-yaml-input" rows={5} value={pasteText} onChange={(e) => setPasteText(e.target.value)} spellCheck={false} autoComplete="off" />
          <div>
            <Button variant="secondary" onClick={applyPaste}>
              Add variables
            </Button>
          </div>
        </div>
      )}
      {pasteNote && (
        <p role="status" className="ed-hint">
          {pasteNote}
        </p>
      )}
    </fieldset>
  )
}
