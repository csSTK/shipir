import { useId, useState } from 'react'
import type { EnvEntry } from '../api.ts'
import { mergeEnv, parseEnv, validateEnv, type EnvIssue } from './envFile.ts'
import './editor.css'
import { Button } from '../../../shared/ui/index.ts'
import { useTranslation } from 'react-i18next'
import i18n from 'i18next'

const ISSUE_TEXT: Record<EnvIssue, string> = {
  get INVALID_KEY() { return i18n.t('deployments:EnvEditor.useLettersDigitsAndUnderscores') },
  get DUPLICATE_KEY() { return i18n.t('deployments:EnvEditor.thisNameIsUsedMore') },
}

/**
 * Key/value editor for deployment variables. Values are masked until revealed; a `.env` text can be pasted
 * and is merged into the rows. Problems are shown per row, saving is up to the caller (see `validateEnv`).
 */
export function EnvEditor({ value, onChange }: { value: EnvEntry[]; onChange: (next: EnvEntry[]) => void }) {
  const { t } = useTranslation('deployments')
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
      setPasteNote(invalidLines.length > 0 ? t('EnvEditor.noKeyValueLinesFound') : t('EnvEditor.nothingToAdd'))
      return
    }
    onChange(mergeEnv(value, entries))
    setPasteText('')
    setPasting(false)
    setPasteNote(
      invalidLines.length > 0
        ? t('EnvEditor.addedSkippedLines', { added: entries.length, count: invalidLines.length, lines: invalidLines.join(', ') })
        : t('EnvEditor.addedVariables', { count: entries.length }),
    )
  }

  return (
    <fieldset className="ed-env">
      <legend>{t('EnvEditor.environmentVariables')}</legend>
      {value.length === 0 && <p className="ed-hint">{t('EnvEditor.noVariables')}</p>}
      {value.map((row, i) => {
        const issue = issues.get(i)
        return (
          <div className="ed-env__item" key={i}>
            <div className="ed-env__row">
              <input
                aria-label={t('EnvEditor.variableValueName', { value: i + 1 })}
                aria-invalid={issue ? true : undefined}
                placeholder={t('EnvEditor.name')}
                value={row.key}
                onChange={(e) => setRow(i, { key: e.target.value })}
                autoComplete="off"
                spellCheck={false}
              />
              <input
                aria-label={t('EnvEditor.variableValueValue', { value: i + 1 })}
                placeholder={t('EnvEditor.value')}
                type={revealed ? 'text' : 'password'}
                value={row.value}
                onChange={(e) => setRow(i, { value: e.target.value })}
                autoComplete="off"
                spellCheck={false}
              />
              <Button variant="ghost" aria-label={t('EnvEditor.removeVariableValue', { value: i + 1 })} onClick={() => onChange(value.filter((_, j) => j !== i))}>
                {t('EnvEditor.remove')}
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
          {t('EnvEditor.addVariable')}
        </Button>
        <Button variant="ghost" aria-expanded={pasting} onClick={() => setPasting((p) => !p)}>
          {t('EnvEditor.pasteEnv')}
        </Button>
        <Button variant="ghost" aria-pressed={revealed} onClick={() => setRevealed((r) => !r)}>
          {revealed ? t('EnvEditor.hideValues') : t('EnvEditor.showValues')}
        </Button>
      </div>
      {pasting && (
        <div className="ed-env__paste">
          <label htmlFor={pasteId} className="ed-hint">
            {t('EnvEditor.pasteTheContentsOfA')}
          </label>
          <textarea id={pasteId} className="ui-yaml-input" rows={5} value={pasteText} onChange={(e) => setPasteText(e.target.value)} spellCheck={false} autoComplete="off" />
          <div>
            <Button variant="secondary" onClick={applyPaste}>
              {t('EnvEditor.addVariables')}
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
